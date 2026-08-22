// Audience CVQ implementation

#include <errno.h>
#include <stdarg.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <stdlib.h>
#include <unistd.h>
#include <dlfcn.h>

#define LOG_TAG "CVQStream"
//#define LOG_NDEBUG 0
//#define LOG_NDDEBUG 0
//#define DEBUG

#ifdef DEBUG

#define STREAMING_DATA_DUMP_FILE          ("/data/dump")
#define STREAMING_DATA_DUMP_FILE_DECODED  ("/data/dumpdecoded")

#endif

#include <utils/Log.h>

#include <cutils/properties.h>
#include <hardware_legacy/power.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <ctype.h>
#include <hardware_legacy/voice_sense.h>
#include <pthread.h>

#include "CVQStream.h"


static short MuLawDecompressTable[256] =
{
     -32124,-31100,-30076,-29052,-28028,-27004,-25980,-24956,
     -23932,-22908,-21884,-20860,-19836,-18812,-17788,-16764,
     -15996,-15484,-14972,-14460,-13948,-13436,-12924,-12412,
     -11900,-11388,-10876,-10364, -9852, -9340, -8828, -8316,
      -7932, -7676, -7420, -7164, -6908, -6652, -6396, -6140,
      -5884, -5628, -5372, -5116, -4860, -4604, -4348, -4092,
      -3900, -3772, -3644, -3516, -3388, -3260, -3132, -3004,
      -2876, -2748, -2620, -2492, -2364, -2236, -2108, -1980,
      -1884, -1820, -1756, -1692, -1628, -1564, -1500, -1436,
      -1372, -1308, -1244, -1180, -1116, -1052,  -988,  -924,
       -876,  -844,  -812,  -780,  -748,  -716,  -684,  -652,
       -620,  -588,  -556,  -524,  -492,  -460,  -428,  -396,
       -372,  -356,  -340,  -324,  -308,  -292,  -276,  -260,
       -244,  -228,  -212,  -196,  -180,  -164,  -148,  -132,
       -120,  -112,  -104,   -96,   -88,   -80,   -72,   -64,
	-56,   -48,   -40,   -32,   -24,   -16,    -8,     -1,
      32124, 31100, 30076, 29052, 28028, 27004, 25980, 24956,
      23932, 22908, 21884, 20860, 19836, 18812, 17788, 16764,
      15996, 15484, 14972, 14460, 13948, 13436, 12924, 12412,
      11900, 11388, 10876, 10364,  9852,  9340,  8828,  8316,
       7932,  7676,  7420,  7164,  6908,  6652,  6396,  6140,
       5884,  5628,  5372,  5116,  4860,  4604,  4348,  4092,
       3900,  3772,  3644,  3516,  3388,  3260,  3132,  3004,
       2876,  2748,  2620,  2492,  2364,  2236,  2108,  1980,
       1884,  1820,  1756,  1692,  1628,  1564,  1500,  1436,
       1372,  1308,  1244,  1180,  1116,  1052,   988,   924,
	876,   844,   812,   780,   748,   716,   684,   652,
	620,   588,   556,   524,   492,   460,   428,   396,
	372,   356,   340,   324,   308,   292,   276,   260,
	244,   228,   212,   196,   180,   164,   148,   132,
	120,   112,   104,    96,    88,    80,    72,    64,
	 56,    48,    40,    32,    24,    16,     8,     0
};

int pcm_read_strm_char_dev(struct CVQStream *cvqStream, void* buffer, int outBytes);

int cvq_open(struct CVQStream *cvqStream)
{
	int ret = 0;
	int err = 0;

	cvqStream->strm_char_dev = fopen(STREAMING_NODE, "rb");

	if (!cvqStream->strm_char_dev)
	{
		ret = -1;
		ALOGE("#### strm_char_dev open failed: %p %d ####",cvqStream->strm_char_dev,errno);
	}
	else {
		ALOGD("#### strm_char_dev open success: %p ####" ,cvqStream->strm_char_dev);
	}
#ifdef DEBUG
	cvqStream->dumpfile = fopen(STREAMING_DATA_DUMP_FILE, "wb");
	if (!cvqStream->dumpfile)
	{
		ret = -1;
		ALOGE("#### dump file open failed: %p %d %s ####",cvqStream->dumpfile, errno, strerror(errno));
	}
	else {
		ALOGD("#### dump file open success: %p ####" ,cvqStream->dumpfile);
	}

	cvqStream->dumpfiledecoded = fopen(STREAMING_DATA_DUMP_FILE_DECODED, "wb");
	if (!cvqStream->dumpfiledecoded)
	{
		ret = -1;
		ALOGE("#### dump file open failed: %p %d %s ####",cvqStream->dumpfiledecoded, errno, strerror(errno));
	}
	else {
		ALOGD("#### dump file open success: %p ####" ,cvqStream->dumpfiledecoded);
	}
#endif  // #ifdef DEBUG

	cvqStream->is_mu_law_encoded = 0;

	return ret;

}

int cvq_close(struct CVQStream *cvqStream)
{
	int ret = 0;
	cvqStream->exit = 1;

	if (cvqStream->strm_char_dev) {
		ret = fclose(cvqStream->strm_char_dev);
		cvqStream->strm_char_dev = NULL;
		ALOGD("#### strm closed, ret = %d ####", ret);
	}

	cvqStream->is_mu_law_encoded = 0;

#ifdef DEBUG
	if (cvqStream->dumpfile) {
		fclose(cvqStream->dumpfile);
	}
	if (cvqStream->dumpfiledecoded) {
		fclose(cvqStream->dumpfiledecoded);
	}
#endif

	return ret;
}

int readdirect(struct CVQStream *cvqStream, void * buffer, int bytes)
{
	int fread_bytes = 0;

    if (cvqStream->strm_char_dev == NULL)
    {
        return 0;
    }

	fread_bytes = pcm_read_strm_char_dev(cvqStream, buffer, bytes);

	return fread_bytes;

}

int strm_read_wrapper(struct CVQStream *cvqStream, unsigned char* temp_buff, size_t size)
{
	int ret = 0;

	if (0 == cvqStream->exit) {
		ALOGV("#### Before STRM read ####");
		ret = fread(temp_buff, size, sizeof(char), cvqStream->strm_char_dev);
		ALOGV("#### After  STRM read (req, actual) = (%d, %d) ####", size * sizeof(char), ret);
	} else {
		ret = -1;
		ALOGE("#### cvqStream->exit = %d, skipping STRM read ... ####", cvqStream->exit);
	}

	return ret;
}


int pcm_read_strm_char_dev(struct CVQStream *cvqStream, void* buffer, int outBytes)
{
	static unsigned char sync_code[2] = {0x12, 0x34};    /* Header */
	unsigned char temp_sync_code[2];
	unsigned char temp_header[6];
	int read_data_bytes = 0;
	unsigned char data;

	short next_id;
	int next_len;
	short next_pktCtr;
	int ret = 0;
        int bytes =  outBytes;
        unsigned char *temp_buff = (unsigned char *)(buffer);
	short* decoded_data;
	int i;

	if (cvqStream->is_mu_law_encoded) {
		bytes =  outBytes/2;
		temp_buff = (unsigned char *)(buffer + bytes);
	}

	while((read_data_bytes < bytes) && (0 == cvqStream->exit)) {

		if (cvqStream->last_frame_remaining_bytes > (bytes - read_data_bytes))
		{
			ret = strm_read_wrapper(
						cvqStream,
						temp_buff,
						(bytes - read_data_bytes)
						);
			if (-1 == ret)
				break;
			else if (0 == ret)
				continue;
#ifdef DEBUG
			if (cvqStream->dumpfile)
				fwrite(temp_buff, sizeof(char), (bytes - read_data_bytes), cvqStream->dumpfile);
#endif
			temp_buff += (bytes - read_data_bytes);
			cvqStream->last_frame_remaining_bytes -= (bytes - read_data_bytes);
			read_data_bytes += (bytes - read_data_bytes);

		}
		else {
			if (cvqStream->last_frame_remaining_bytes > 0) {
				ret = strm_read_wrapper(
							cvqStream,
							temp_buff,
							cvqStream->last_frame_remaining_bytes
							);
				if (-1 == ret)
					break;
				else if (0 == ret)
					continue;
#ifdef DEBUG
				if (cvqStream->dumpfile)
					fwrite(temp_buff, sizeof(char), cvqStream->last_frame_remaining_bytes, cvqStream->dumpfile);
#endif
				temp_buff += cvqStream->last_frame_remaining_bytes;
				read_data_bytes += cvqStream->last_frame_remaining_bytes;
				cvqStream->last_frame_remaining_bytes = 0;
			}

			ret = strm_read_wrapper(
						cvqStream,
						temp_sync_code,
						2
						);
			if (-1 == ret)
				break;
			else if (0 == ret)
				continue;
#ifdef DEBUG
			if (cvqStream->dumpfile)
				fwrite(temp_sync_code, sizeof(char),  2, cvqStream->dumpfile);
#endif
			while (!(temp_sync_code[0] == sync_code[0] && temp_sync_code[1] == sync_code[1]) && (0 == cvqStream->exit)) {
				if (temp_sync_code[1] == sync_code[0]) {
					temp_sync_code[0] = sync_code[0];
					ret = strm_read_wrapper(
								cvqStream,
								&temp_sync_code[1],
								1
								);
					if (-1 == ret)
						break;
					else if (0 == ret)
						continue;
#ifdef DEBUG
					if (cvqStream->dumpfile)
						fwrite(&temp_sync_code[1], sizeof(char),  1, cvqStream->dumpfile);
#endif
				} else {
					ret = strm_read_wrapper(
								cvqStream,
								temp_sync_code,
								2
								);
					if (-1 == ret)
						break;
					else if (0 == ret)
						continue;
#ifdef DEBUG
					if (cvqStream->dumpfile)
						fwrite(temp_sync_code, sizeof(char),  2, cvqStream->dumpfile);
#endif
				} // if (temp_sync_code[1] == sync_code[0]) { ... } else { ...

			} // while (!(temp_sync_code[0] == sync_code[0] && temp_sync_code[1] == sync_code[1]) && (0 == cvqStream->exit)) { ...

//sync word found read the header
			ret = strm_read_wrapper(cvqStream, temp_header, 6);
			if (-1 == ret)
				break;
			else if (0 == ret)
				continue;
#ifdef DEBUG
			if (cvqStream->dumpfile)
				fwrite(temp_header, sizeof(char),  6, cvqStream->dumpfile);
#endif
			/* Get the ID */
			data = temp_header[0];
			next_id = (short)data;

			/* Get the packet counter */
			data = temp_header[1];
			next_id |= (short)((data & 0xf0) << 4);
			next_pktCtr = (short)(data & 0xf);

			/* Get the length */
			next_len = 0;
			data = temp_header[2];
			next_len = (short)data;
			data = temp_header[3];
			next_len |= (short)(data << 8);

			/* Get last frame indicator */
			//data = temp_header[4];
			//next_len |= (short)(data << 16);
			data = temp_header[5];
			cvqStream->is_mu_law_encoded = !!((int)data & 0x0040);

			//Depending upon the header we update the format(mu_law or not)
			if (cvqStream->is_mu_law_encoded) {
				if ( bytes == outBytes){
					bytes =  (outBytes/2);
					temp_buff = (unsigned char *)(buffer + bytes);
				}
			}

			switch (next_len)
			{
			    case 80:
			    case 128:
			    case 160:
			    case 256:
			    case 320:
			    case 480:
			    case 640:
			    case 1280:
				ret = 0;
				break;

			    default:
			    next_len = 0;

			} // switch (next_len)

			cvqStream->last_frame_remaining_bytes = next_len;
			ALOGD("#### Frame found - <ID : PKT # : LEN> = <%5d : %5d : %5d> ####", next_id, next_pktCtr, next_len);

		} // if (cvqStream->last_frame_remaining_bytes > (bytes - read_data_bytes)) { ... } else { ...

	} // while((read_data_bytes < bytes) && (0 == cvqStream->exit)) { ...

	decoded_data = (short*) buffer;

	if (cvqStream->is_mu_law_encoded) {
		temp_buff = (unsigned char*)(buffer + bytes);
		for(i = 0; i < read_data_bytes; i++)
		{
			decoded_data[i] = MuLawDecompressTable[(unsigned int)(temp_buff[i])];
		}
		read_data_bytes = read_data_bytes * 2;
	}

#ifdef DEBUG
	if (cvqStream->dumpfiledecoded)
		fwrite(decoded_data, sizeof(char), read_data_bytes, cvqStream->dumpfiledecoded);
#endif

	return read_data_bytes;
}

int cvq_init(struct CVQStream *cvqStream)
{
	cvqStream->open = cvq_open;
	cvqStream->close = cvq_close;
	cvqStream->readdirect = readdirect;

	cvqStream->strm_char_dev = NULL,
	cvqStream->last_frame_remaining_bytes = 0;
	cvqStream->is_mu_law_encoded = 0;
#ifdef DEBUG
	cvqStream->dumpfile = NULL;
	cvqStream->dumpfiledecoded = NULL;
#endif
	cvqStream->exit = 0;

	return 0;
}

