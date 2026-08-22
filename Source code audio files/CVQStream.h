// Audience CVQ implementation

#ifndef CVQ_STREAM_H
#define CVQ_STREAM_H

#include <stdio.h>
#include <pthread.h>

#ifdef __cplusplus
extern "C" {
#endif


#define AUDIENCE_CVQ_OK_GOOGLE_PROP     ("persist.sys.adnc.okgoogle")
#define BUFFER_DATA_SEC                 (6)
#define CVQ_REC_SAMPLING_FREQ           (16000)
#define STRM_DATA_BUF_SIZE              (2 * BUFFER_DATA_SEC * CVQ_REC_SAMPLING_FREQ)
#define STREAMING_NODE                  ("/dev/adnc2")

struct CVQStream
{
    int is_mu_law_encoded;
    pthread_mutex_t lock;
    FILE* strm_char_dev;
    FILE* dumpfile;
    FILE* dumpfiledecoded;
    int last_frame_remaining_bytes;
    int exit;
    int (*open) (struct CVQStream *cvqStream);
    int (*close) (struct CVQStream *cvqStream);
    int (*readdirect) (struct CVQStream *cvqStream, void * buf, int bytes);
};

int cvq_init(struct CVQStream *cvqStream);


#ifdef __cplusplus
}
#endif


#endif
