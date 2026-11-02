/* Kasseta native core — see kcore.h */
#include "kcore.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/opt.h>
#include <libavutil/avstring.h>
#include <libavutil/channel_layout.h>
#include <libswresample/swresample.h>

struct KDec {
	AVFormatContext *fmt;
	AVCodecContext *cc;
	AVStream *st;
	int si;
	AVPacket *pkt;
	AVFrame *frm;

	SwrContext *swr;
	int swr_in_rate, swr_in_fmt, swr_out_ch;
	AVChannelLayout swr_in_layout;

	int out_rate, dsd_filter;
	float gain;

	float *buf;           /* decoded output, swr_out_ch interleaved */
	int buf_cap, buf_len, buf_pos;

	int64_t st_start;     /* stream start time in stream time base */
	int64_t cur_src;      /* running position in source samples */
	int64_t skip_to;      /* drop source samples before this (after a seek), -1 = none */
	int64_t data_start;   /* byte offset of the audio payload (for byte-seek fallback) */
	int demux_eof, dec_eof, done;

	KInfo info;
};

static int is_dsd_codec(enum AVCodecID id) {
	return id == AV_CODEC_ID_DSD_LSBF || id == AV_CODEC_ID_DSD_MSBF ||
	       id == AV_CODEC_ID_DSD_LSBF_PLANAR || id == AV_CODEC_ID_DSD_MSBF_PLANAR ||
	       id == AV_CODEC_ID_DST;
}

static void set_err(char *err, int len, const char *what, int code) {
	if (!err || len <= 0) return;
	char e[128] = "";
	if (code) av_strerror(code, e, sizeof e);
	snprintf(err, len, "%s%s%s", what, code ? ": " : "", e);
}

static int open_input(AVFormatContext **fmt, const char *path, int quick) {
	AVDictionary *opt = NULL;
	if (quick) {
		av_dict_set(&opt, "probesize", "1048576", 0);
		av_dict_set(&opt, "analyzeduration", "2000000", 0);
	}
	int r = avformat_open_input(fmt, path, NULL, &opt);
	av_dict_free(&opt);
	return r;
}

KDec *kd_open(const char *path, int max_rate, int dsd_filter, char *err, int errlen) {
	KDec *d = calloc(1, sizeof *d);
	if (!d) return NULL;
	d->gain = 1.0f;
	d->skip_to = -1;
	d->dsd_filter = dsd_filter;
	int r = open_input(&d->fmt, path, 0);
	if (r < 0) { set_err(err, errlen, "open", r); free(d); return NULL; }
	d->data_start = d->fmt->pb ? avio_tell(d->fmt->pb) : 0;
	r = avformat_find_stream_info(d->fmt, NULL);
	if (r < 0) { set_err(err, errlen, "stream info", r); kd_close(d); return NULL; }
	const AVCodec *codec = NULL;
	d->si = av_find_best_stream(d->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
	if (d->si < 0 || !codec) { set_err(err, errlen, "no audio stream / unsupported codec", d->si < 0 ? d->si : 0); kd_close(d); return NULL; }
	for (unsigned i = 0; i < d->fmt->nb_streams; i++)
		if ((int)i != d->si) d->fmt->streams[i]->discard = AVDISCARD_ALL;
	d->st = d->fmt->streams[d->si];
	d->cc = avcodec_alloc_context3(codec);
	if (!d->cc) { set_err(err, errlen, "no memory", 0); kd_close(d); return NULL; }
	avcodec_parameters_to_context(d->cc, d->st->codecpar);
	d->cc->pkt_timebase = d->st->time_base;
	r = avcodec_open2(d->cc, codec, NULL);
	if (r < 0) { set_err(err, errlen, "decoder", r); kd_close(d); return NULL; }
	d->pkt = av_packet_alloc();
	d->frm = av_frame_alloc();
	if (!d->pkt || !d->frm) { set_err(err, errlen, "no memory", 0); kd_close(d); return NULL; }
	d->st_start = d->st->start_time != AV_NOPTS_VALUE ? d->st->start_time : 0;

	AVCodecParameters *cp = d->st->codecpar;
	KInfo *in = &d->info;
	in->src_rate = cp->sample_rate > 0 ? cp->sample_rate : d->cc->sample_rate;
	in->src_ch = cp->ch_layout.nb_channels;
	in->is_dsd = is_dsd_codec(cp->codec_id);
	in->dsd_rate = in->is_dsd ? in->src_rate * 8 : 0;
	in->bits = in->is_dsd ? 1 : (cp->bits_per_raw_sample ? cp->bits_per_raw_sample : cp->bits_per_coded_sample);
	if (d->fmt->duration > 0) in->dur_ms = d->fmt->duration / 1000;
	else if (d->st->duration > 0) in->dur_ms = av_rescale_q(d->st->duration, d->st->time_base, (AVRational){1, 1000});
	in->bitrate = (int)((d->fmt->bit_rate > 0 ? d->fmt->bit_rate : cp->bit_rate) / 1000);
	snprintf(in->codec, sizeof in->codec, "%s", avcodec_get_name(cp->codec_id));
	if (max_rate < 8000) max_rate = 192000;
	int rate = in->src_rate > 0 ? in->src_rate : 44100;
	while (rate > max_rate) rate /= 2;
	d->out_rate = rate;
	in->out_rate = rate;
	in->out_ch = 2;
	return d;
}

void kd_info(KDec *d, KInfo *out) { *out = d->info; }
void kd_set_gain(KDec *d, float g) { d->gain = g; }

static int setup_swr(KDec *d, AVFrame *f) {
	AVChannelLayout in_l = { 0 }; /* must be zeroed: av_channel_layout_copy() uninits the destination first */
	if (f->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC || !f->ch_layout.nb_channels)
		av_channel_layout_default(&in_l, f->ch_layout.nb_channels ? f->ch_layout.nb_channels : d->info.src_ch);
	else av_channel_layout_copy(&in_l, &f->ch_layout);
	if (d->swr && d->swr_in_rate == f->sample_rate && d->swr_in_fmt == f->format &&
	    !av_channel_layout_compare(&in_l, &d->swr_in_layout)) {
		av_channel_layout_uninit(&in_l);
		return 0;
	}
	swr_free(&d->swr);
	av_channel_layout_uninit(&d->swr_in_layout);
	int mono = in_l.nb_channels == 1;
	AVChannelLayout out_l = mono ? (AVChannelLayout)AV_CHANNEL_LAYOUT_MONO : (AVChannelLayout)AV_CHANNEL_LAYOUT_STEREO;
	int r = swr_alloc_set_opts2(&d->swr, &out_l, AV_SAMPLE_FMT_FLT, d->out_rate,
	                            &in_l, f->format, f->sample_rate, 0, NULL);
	if (r < 0 || !d->swr) { av_channel_layout_uninit(&in_l); return r < 0 ? r : AVERROR(ENOMEM); }
	if (f->sample_rate != d->out_rate) {
		av_opt_set_int(d->swr, "filter_size", 48, 0);
		av_opt_set_int(d->swr, "phase_shift", 12, 0);
		av_opt_set_int(d->swr, "linear_interp", 1, 0);
		double cutoff = 0.97;
		if (d->info.is_dsd && d->dsd_filter) {
			double c = 45000.0 / (d->out_rate / 2.0);
			if (c < cutoff) cutoff = c;
		}
		av_opt_set_double(d->swr, "cutoff", cutoff, 0);
	}
	if (in_l.nb_channels > 2) av_opt_set_int(d->swr, "clip_protection", 1, 0);
	r = swr_init(d->swr);
	if (r < 0) { swr_free(&d->swr); av_channel_layout_uninit(&in_l); return r; }
	d->swr_in_rate = f->sample_rate;
	d->swr_in_fmt = f->format;
	d->swr_in_layout = in_l;
	d->swr_out_ch = mono ? 1 : 2;
	return 0;
}

static int ensure_buf(KDec *d, int extra_frames) {
	int need = (d->buf_len + extra_frames) * 2;
	if (need <= d->buf_cap) return 0;
	int cap = d->buf_cap ? d->buf_cap : 8192;
	while (cap < need) cap *= 2;
	float *nb = realloc(d->buf, (size_t)cap * sizeof(float));
	if (!nb) return AVERROR(ENOMEM);
	d->buf = nb;
	d->buf_cap = cap;
	return 0;
}

static int process_frame(KDec *d, AVFrame *f) {
	int nb = f->nb_samples;
	if (nb <= 0) return 0;
	int64_t s = d->cur_src;
	int64_t ts = f->best_effort_timestamp;
	if (ts != AV_NOPTS_VALUE && f->sample_rate > 0)
		s = av_rescale_q(ts - d->st_start, d->st->time_base, (AVRational){1, f->sample_rate});
	d->cur_src = s + nb;
	int off = 0;
	if (d->skip_to >= 0) {
		if (s + nb <= d->skip_to) return 0;
		if (d->skip_to > s) off = (int)(d->skip_to - s);
		d->skip_to = -1;
	}
	int r = setup_swr(d, f);
	if (r < 0) return r;
	const uint8_t *in[AV_NUM_DATA_POINTERS] = {0};
	int bps = av_get_bytes_per_sample(f->format);
	int planar = av_sample_fmt_is_planar(f->format);
	int ch = f->ch_layout.nb_channels;
	for (int i = 0; i < (planar ? ch : 1) && i < AV_NUM_DATA_POINTERS; i++)
		in[i] = f->extended_data[i] + (size_t)off * bps * (planar ? 1 : ch);
	if (planar && ch > AV_NUM_DATA_POINTERS) return AVERROR_PATCHWELCOME;
	int cnt = nb - off;
	int outn = swr_get_out_samples(d->swr, cnt) + 32;
	if ((r = ensure_buf(d, outn)) < 0) return r;
	uint8_t *o = (uint8_t *)(d->buf + (size_t)d->buf_len * d->swr_out_ch);
	int got = swr_convert(d->swr, &o, outn, in, cnt);
	if (got < 0) return got;
	d->buf_len += got;
	return 0;
}

static int flush_swr(KDec *d) {
	if (!d->swr) return 0;
	int outn = swr_get_out_samples(d->swr, 0) + 32;
	if (ensure_buf(d, outn) < 0) return AVERROR(ENOMEM);
	uint8_t *o = (uint8_t *)(d->buf + (size_t)d->buf_len * d->swr_out_ch);
	int got = swr_convert(d->swr, &o, outn, NULL, 0);
	if (got > 0) d->buf_len += got;
	return 0;
}

/* decode until some output is buffered or the stream ends */
static int step(KDec *d) {
	int errors = 0;
	while (d->buf_len - d->buf_pos <= 0 && !d->done) {
		int r = avcodec_receive_frame(d->cc, d->frm);
		if (r == 0) {
			r = process_frame(d, d->frm);
			av_frame_unref(d->frm);
			if (r < 0) return r;
			continue;
		}
		if (r == AVERROR_EOF) {
			flush_swr(d);
			d->done = 1;
			break;
		}
		if (r != AVERROR(EAGAIN)) {
			/* corrupt data: try to carry on with the next packet */
			if (++errors > 64) return r;
		}
		if (d->demux_eof) {
			/* decoder wants input but we have none left */
			avcodec_send_packet(d->cc, NULL);
			continue;
		}
		r = av_read_frame(d->fmt, d->pkt);
		if (r < 0) {
			d->demux_eof = 1;
			avcodec_send_packet(d->cc, NULL);
			continue;
		}
		if (d->pkt->stream_index == d->si) {
			int s = avcodec_send_packet(d->cc, d->pkt);
			if (s < 0 && s != AVERROR(EAGAIN) && ++errors > 64) { av_packet_unref(d->pkt); return s; }
		}
		av_packet_unref(d->pkt);
	}
	return 0;
}

int kd_read(KDec *d, float *out, int max_frames) {
	if (d->buf_pos >= d->buf_len) {
		d->buf_pos = d->buf_len = 0;
		int r = step(d);
		if (r < 0) return r;
	}
	int avail = d->buf_len - d->buf_pos;
	if (avail <= 0) return 0;
	int n = avail < max_frames ? avail : max_frames;
	const float g = d->gain;
	if (d->swr_out_ch == 1) {
		const float *src = d->buf + d->buf_pos;
		for (int i = 0; i < n; i++) out[2 * i] = out[2 * i + 1] = src[i] * g;
	} else {
		const float *src = d->buf + (size_t)d->buf_pos * 2;
		if (g == 1.0f) memcpy(out, src, (size_t)n * 2 * sizeof(float));
		else for (int i = 0; i < n * 2; i++) out[i] = src[i] * g;
	}
	d->buf_pos += n;
	return n;
}

int kd_seek(KDec *d, int64_t ms) {
	if (ms < 0) ms = 0;
	int sr = d->info.src_rate > 0 ? d->info.src_rate : 44100;
	int64_t target = av_rescale(ms, sr, 1000);
	/* seek a little earlier so lossy decoders get their overlap/priming data, then drop up to target */
	int64_t pre = target - sr / 5;
	if (pre < 0) pre = 0;
	int64_t ts = av_rescale_q(pre, (AVRational){1, sr}, d->st->time_base) + d->st_start;
	int r = av_seek_frame(d->fmt, d->si, ts, AVSEEK_FLAG_BACKWARD);
	int64_t pos_guess = -1;
	if (r < 0) {
		/* raw formats without an index (DSF, DFF): seek by bytes, they are constant bit rate */
		AVCodecParameters *cp = d->st->codecpar;
		int64_t br = cp->bit_rate > 0 ? cp->bit_rate : d->fmt->bit_rate;
		if (d->info.is_dsd && cp->codec_id != AV_CODEC_ID_DST)
			br = (int64_t)sr * 8 * (d->info.src_ch > 0 ? d->info.src_ch : 2);
		if (br > 0) {
			int64_t bytes = av_rescale(target, br, (int64_t)sr * 8);
			int ba = cp->block_align > 0 ? cp->block_align : 1;
			bytes = bytes / ba * ba;
			r = avformat_seek_file(d->fmt, d->si, INT64_MIN, d->data_start + bytes, INT64_MAX, AVSEEK_FLAG_BYTE);
			if (r >= 0) pos_guess = av_rescale(bytes, (int64_t)sr * 8, br);
		}
	}
	if (r < 0) {
		/* last resort: restart from the beginning and decode up to the target */
		r = avformat_seek_file(d->fmt, d->si, INT64_MIN, d->st_start, d->st_start, 0);
		if (r < 0) r = avio_seek(d->fmt->pb, d->data_start, SEEK_SET) < 0 ? -1 : 0;
		pos_guess = 0;
		if (r < 0) return r;
	}
	avcodec_flush_buffers(d->cc);
	if (d->swr) { swr_free(&d->swr); av_channel_layout_uninit(&d->swr_in_layout); }
	d->buf_len = d->buf_pos = 0;
	d->demux_eof = d->dec_eof = d->done = 0;
	d->cur_src = pos_guess >= 0 ? pos_guess : target;
	d->skip_to = target;
	return 0;
}

void kd_close(KDec *d) {
	if (!d) return;
	swr_free(&d->swr);
	av_channel_layout_uninit(&d->swr_in_layout);
	av_frame_free(&d->frm);
	av_packet_free(&d->pkt);
	avcodec_free_context(&d->cc);
	avformat_close_input(&d->fmt);
	free(d->buf);
	free(d);
}

/* ---------- probing ---------- */

typedef struct { char *p; int len, cap; } SB;

static void sb_add(SB *b, const char *s, int n) {
	if (n < 0) n = (int)strlen(s);
	if (b->len + n + 1 > b->cap) {
		int cap = b->cap ? b->cap : 1024;
		while (cap < b->len + n + 1) cap *= 2;
		char *np = realloc(b->p, cap);
		if (!np) return;
		b->p = np; b->cap = cap;
	}
	memcpy(b->p + b->len, s, n);
	b->len += n;
	b->p[b->len++] = 0;
}

static void sb_kv(SB *b, const char *k, const char *v) {
	char key[96];
	int i = 0;
	for (; k[i] && i < (int)sizeof key - 1; i++) key[i] = (char)tolower((unsigned char)k[i]);
	key[i] = 0;
	sb_add(b, key, -1);
	sb_add(b, v ? v : "", -1);
}

static void sb_kvi(SB *b, const char *k, int64_t v) {
	char s[32];
	snprintf(s, sizeof s, "%lld", (long long)v);
	sb_kv(b, k, s);
}

/* true when the container header already told us everything (saves reading packets on big scans) */
static int header_is_enough(AVFormatContext *fmt) {
	int audio = 0;
	for (unsigned i = 0; i < fmt->nb_streams; i++) {
		AVStream *s = fmt->streams[i];
		if (s->codecpar->codec_type != AVMEDIA_TYPE_AUDIO) continue;
		audio++;
		if (s->codecpar->codec_id == AV_CODEC_ID_NONE || s->codecpar->sample_rate <= 0 ||
		    s->codecpar->ch_layout.nb_channels <= 0 || s->duration <= 0 || s->duration == AV_NOPTS_VALUE)
			return 0;
	}
	return audio == 1;
}

char *kp_probe(const char *path, int *len) {
	AVFormatContext *fmt = NULL;
	if (open_input(&fmt, path, 1) < 0) return NULL;
	int quick = header_is_enough(fmt);
	if (!quick && avformat_find_stream_info(fmt, NULL) < 0) { avformat_close_input(&fmt); return NULL; }
	int si = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
	if (si < 0) { avformat_close_input(&fmt); return NULL; }
	AVStream *st = fmt->streams[si];
	AVCodecParameters *cp = st->codecpar;
	SB b = {0};
	int64_t dur = 0;
	if (fmt->duration > 0 && fmt->duration != AV_NOPTS_VALUE) dur = fmt->duration / 1000;
	else if (st->duration > 0) dur = av_rescale_q(st->duration, st->time_base, (AVRational){1, 1000});
	int64_t br = fmt->bit_rate > 0 ? fmt->bit_rate : cp->bit_rate;
	if (br <= 0 && dur > 0 && fmt->pb) {
		int64_t size = avio_size(fmt->pb);
		if (size > 0) br = size * 8 * 1000 / dur;
	}
	sb_kvi(&b, "_dur_ms", dur);
	sb_kvi(&b, "_rate", cp->sample_rate);
	sb_kvi(&b, "_ch", cp->ch_layout.nb_channels);
	int dsd = is_dsd_codec(cp->codec_id);
	sb_kvi(&b, "_bits", dsd ? 1 : (cp->bits_per_raw_sample ? cp->bits_per_raw_sample : cp->bits_per_coded_sample));
	if (dsd) sb_kvi(&b, "_dsd", (int64_t)cp->sample_rate * 8);
	sb_kv(&b, "_codec", avcodec_get_name(cp->codec_id));
	sb_kv(&b, "_format", fmt->iformat ? fmt->iformat->name : "");
	sb_kvi(&b, "_bitrate", br / 1000);
	int cover = 0;
	for (unsigned i = 0; i < fmt->nb_streams; i++) {
		AVStream *s = fmt->streams[i];
		if ((s->disposition & AV_DISPOSITION_ATTACHED_PIC) && s->attached_pic.size > 0) cover = 1;
		if (s->codecpar->codec_type == AVMEDIA_TYPE_ATTACHMENT && s->codecpar->extradata_size > 0) {
			const AVDictionaryEntry *m = av_dict_get(s->metadata, "mimetype", NULL, 0);
			if (m && !strncmp(m->value, "image/", 6)) cover = 1;
		}
	}
	sb_kvi(&b, "_cover", cover);
	const AVDictionaryEntry *e = NULL;
	while ((e = av_dict_iterate(st->metadata, e))) sb_kv(&b, e->key, e->value);
	e = NULL;
	while ((e = av_dict_iterate(fmt->metadata, e))) sb_kv(&b, e->key, e->value);
	for (unsigned i = 0; i < fmt->nb_chapters; i++) {
		AVChapter *c = fmt->chapters[i];
		const AVDictionaryEntry *t = av_dict_get(c->metadata, "title", NULL, 0);
		char v[600];
		snprintf(v, sizeof v, "%lld|%lld|%s",
		         (long long)av_rescale_q(c->start, c->time_base, (AVRational){1, 1000}),
		         (long long)av_rescale_q(c->end, c->time_base, (AVRational){1, 1000}),
		         t ? t->value : "");
		sb_kv(&b, "_chapter", v);
	}
	avformat_close_input(&fmt);
	*len = b.len;
	return b.p;
}

uint8_t *kp_cover(const char *path, int *len) {
	AVFormatContext *fmt = NULL;
	if (open_input(&fmt, path, 1) < 0) return NULL;
	const uint8_t *best = NULL;
	int best_len = 0, best_score = -1;
	for (unsigned i = 0; i < fmt->nb_streams; i++) {
		AVStream *s = fmt->streams[i];
		const uint8_t *p = NULL;
		int n = 0;
		if ((s->disposition & AV_DISPOSITION_ATTACHED_PIC) && s->attached_pic.size > 0) {
			p = s->attached_pic.data; n = s->attached_pic.size;
		} else if (s->codecpar->codec_type == AVMEDIA_TYPE_ATTACHMENT && s->codecpar->extradata_size > 0) {
			const AVDictionaryEntry *m = av_dict_get(s->metadata, "mimetype", NULL, 0);
			if (m && !strncmp(m->value, "image/", 6)) { p = s->codecpar->extradata; n = s->codecpar->extradata_size; }
		}
		if (!p) continue;
		const AVDictionaryEntry *c = av_dict_get(s->metadata, "comment", NULL, 0);
		const AVDictionaryEntry *fn = av_dict_get(s->metadata, "filename", NULL, 0);
		int score = 1;
		if (c && av_stristr(c->value, "front")) score = 3;
		if (fn && (av_stristr(fn->value, "cover") || av_stristr(fn->value, "front"))) score = 3;
		if (score > best_score) { best = p; best_len = n; best_score = score; }
	}
	uint8_t *out = NULL;
	if (best && best_len > 0 && (out = malloc(best_len))) {
		memcpy(out, best, best_len);
		*len = best_len;
	}
	avformat_close_input(&fmt);
	return out;
}
