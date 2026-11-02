/* Kasseta native core: decoding any format FFmpeg knows into float PCM, tags, covers. */
#ifndef KCORE_H
#define KCORE_H
#include <stdint.h>

typedef struct KDec KDec;

typedef struct {
	int out_rate, out_ch;        /* what kd_read produces (float, interleaved) */
	int src_rate, src_ch, bits;  /* source stream */
	int is_dsd, dsd_rate;        /* DSD: native 1-bit rate, e.g. 2822400 */
	int64_t dur_ms;
	int bitrate;                 /* kbit/s, 0 if unknown */
	char codec[32];
} KInfo;

/* max_rate caps the output sample rate (rates above it are halved until they fit, so the
 * 44.1/48 kHz family is kept). dsd_filter: 1 = extra ~45 kHz low-pass against DSD noise. */
KDec *kd_open(const char *path, int max_rate, int dsd_filter, char *err, int errlen);
void kd_info(KDec *d, KInfo *out);
/* returns frames written (out holds frames*out_ch floats), 0 at end of stream, <0 on error */
int kd_read(KDec *d, float *out, int max_frames);
/* sample-accurate seek; returns 0 on success */
int kd_seek(KDec *d, int64_t ms);
void kd_set_gain(KDec *d, float linear);
void kd_close(KDec *d);

/* Tags & stream info as "key\0value\0key\0value\0...". Caller frees with free(). NULL on error.
 * Keys are lower-case. Special keys: _dur_ms, _rate, _ch, _bits, _codec, _bitrate, _dsd,
 * _cover (1 if embedded picture), _chapter ("start_ms|end_ms|title", may repeat). */
char *kp_probe(const char *path, int *len);
/* embedded cover picture bytes (JPEG/PNG...). Caller frees. NULL if none. */
uint8_t *kp_cover(const char *path, int *len);

#endif
