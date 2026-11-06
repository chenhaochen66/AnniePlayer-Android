/*
 * DSDIFF (Direct Stream Digital Interchange File Format) demuxer
 *
 * 本项目自带（upstream ffmpeg 至今只有 DSF，没有 DSDIFF；ffmpeg-kit 也没有）。
 * 按 Sony "DSDIFF File Format Specification 1.5" 实现最小可用版本：
 *
 *   FRM8 <u64 BE size> "DSD "
 *     FVER <u64>  "01.05.00"
 *     PROP <u64>  "SND "
 *       FS   <u64=4>  <u32 BE 1-bit 采样率>      // DSD64 = 2822400
 *       CHNL <u64>    <u16 BE 声道数> <4B 声道 ID>…
 *       CMPR <u64>    "DSD " <压缩名>
 *     DSD  <u64>  <裸 DSD 数据，MSB first>
 *
 * 与 DSF 的四处关键差异（写的时候最容易错在这）：
 *   1. 所有 chunk size 是 **big-endian u64**，且 **包含自身的 12 字节头**；
 *      DSF 是 little-endian，且只含 size 之后的长度。
 *   2. FRM8 / PROP 的 size 之后各跟 4 字节 form/prop 类型，再才是子 chunk。
 *   3. 子 chunk payload 为奇数时要补 1 字节 padding（chunk 间偶对齐）。
 *   4. 数据是 **interleaved** MSB-first → AV_CODEC_ID_DSD_MSBF（非 planar）；
 *      DSF 是 block-interleaved → DSD_MSBF_PLANAR。
 *
 * 不支持 DST（压缩 DSD）—— 那需要单独的 DST 解码器，本项目用不到。
 *
 * This file is part of FFmpeg.
 * FFmpeg is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 */

#include "libavutil/channel_layout.h"
#include "libavutil/intreadwrite.h"

#include "avformat.h"
#include "demux.h"
#include "internal.h"

/** DSDIFF 规范里 SD 声道最多 6 个，这里放宽到 8（个别母带是 8ch） */
#define DSDIFF_MAX_CHANNELS 8

/** 单包大小：太大 Media3 侧缓冲多，太小 seek 粒度细但 IO 次数多 */
#define DSDIFF_PACKET_SIZE (16 * 1024)

typedef struct DSDIFFContext {
    int64_t data_offset;
    int64_t data_end;
    int     channels;
} DSDIFFContext;

static int dsdiff_probe(const AVProbeData *p)
{
    if (p->buf_size < 16)
        return 0;
    /* FRM8 <8B size> "DSD " */
    if (memcmp(p->buf, "FRM8", 4) || memcmp(p->buf + 12, "DSD ", 4))
        return 0;
    return AVPROBE_SCORE_MAX;
}

/**
 * 读一个 chunk 头（4 字节 id + 8 字节 BE size）。
 *
 * @param payload    size 扣除 12 字节头之后的有效负载长度
 * @param field_size 原始 size 字段（含头），用于计算 chunk 结束位置
 */
static int read_chunk_header(AVIOContext *pb, uint32_t *id, int64_t *payload,
                            int64_t *field_size)
{
    *id         = avio_rb32(pb);
    *field_size = (int64_t)avio_rb64(pb);
    if (*field_size < 12)
        return AVERROR_INVALIDDATA;
    *payload = *field_size - 12;
    return 0;
}

/** chunk 之后的下一个位置（payload 为奇数时补 1 字节 padding） */
static int64_t chunk_next(int64_t pos, int64_t payload)
{
    return pos + payload + (payload & 1);
}

/** 解析 PROP/SND 里的 FS + CHNL，填 stream 参数 */
static int dsdiff_read_prop(AVFormatContext *s, DSDIFFContext *c, int64_t prop_end)
{
    AVIOContext *pb = s->pb;
    AVStream *st = s->streams[0];
    uint32_t dsd_rate = 0;
    int channels = 0;

    while (avio_tell(pb) + 12 <= prop_end) {
        uint32_t id;
        int64_t payload, field;
        if (read_chunk_header(pb, &id, &payload, &field) < 0)
            break;

        const int64_t next = chunk_next(avio_tell(pb), payload);

        if (id == MKBETAG('F', 'S', ' ', ' ')) {
            if (payload >= 4)
                dsd_rate = avio_rb32(pb);
        } else if (id == MKBETAG('C', 'H', 'N', 'L')) {
            if (payload >= 2) {
                const int n = avio_rb16(pb);
                if (n <= 0 || n > DSDIFF_MAX_CHANNELS)
                    return AVERROR_INVALIDDATA;
                channels = n;
                /* 后面是 n 个 4 字节声道 ID，对播放没用 */
            }
        }

        if (next > prop_end)
            return AVERROR_INVALIDDATA;
        if (avio_seek(pb, next, SEEK_SET) < 0)
            return AVERROR_INVALIDDATA;
    }

    if (!dsd_rate || !channels)
        return AVERROR_INVALIDDATA;

    c->channels = channels;

    st->codecpar->codec_type = AVMEDIA_TYPE_AUDIO;
    /* 与 DSF 一致：decoder 按「每字节 8 个 1-bit 样本」处理，所以采样率要 /8。
     * 不 /8 的话 PTS 会以 1-bit 率推进，200 多秒的歌会被算成半小时以上。 */
    st->codecpar->sample_rate = dsd_rate / 8;
    if (st->codecpar->sample_rate <= 0)
        return AVERROR_INVALIDDATA;
    av_channel_layout_default(&st->codecpar->ch_layout, channels);
    /* DSDIFF 是 interleaved MSB-first（每样本 1 字节/声道），不是 planar */
    st->codecpar->codec_id   = AV_CODEC_ID_DSD_MSBF;
    st->codecpar->block_align = DSDIFF_PACKET_SIZE;
    st->codecpar->bit_rate   = channels * 8LL * st->codecpar->sample_rate;
    avpriv_set_pts_info(st, 64, 1, st->codecpar->sample_rate);
    return 0;
}

static int dsdiff_read_header(AVFormatContext *s)
{
    DSDIFFContext *c = s->priv_data;
    AVIOContext *pb = s->pb;
    AVStream *st;
    int64_t frame_size, frm8_end;
    uint32_t form;

    st = avformat_new_stream(s, NULL);
    if (!st)
        return AVERROR(ENOMEM);

    if (avio_rb32(pb) != MKBETAG('F', 'R', 'M', '8'))
        return AVERROR_INVALIDDATA;
    frame_size = (int64_t)avio_rb64(pb);
    if (frame_size < 16)
        return AVERROR_INVALIDDATA;
    form = avio_rb32(pb);
    if (form != MKBETAG('D', 'S', 'D', ' ')) {
        avpriv_request_sample(s, "non-DSD form %"PRIx32, form);
        return AVERROR_INVALIDDATA;
    }

    /* FRM8 的 size 含 12 字节头，所以整体结束位置是 12 + frame_size */
    frm8_end = 12 + frame_size;

    while (avio_tell(pb) + 12 <= frm8_end) {
        uint32_t id;
        int64_t payload, field;
        if (read_chunk_header(pb, &id, &payload, &field) < 0)
            break;

        const int64_t body  = avio_tell(pb);
        const int64_t next  = chunk_next(body, payload);

        if (id == MKBETAG('P', 'R', 'O', 'P')) {
            const uint32_t prop_type = avio_rb32(pb);
            if (prop_type != MKBETAG('S', 'N', 'D', ' ')) {
                avpriv_request_sample(s, "unsupported PROP %"PRIx32, prop_type);
                return AVERROR_INVALIDDATA;
            }
            /* prop type 占 4 字节，剩下的才是子 chunk 区 */
            int ret = dsdiff_read_prop(s, c, body + payload);
            if (ret < 0)
                return ret;
        } else if (id == MKBETAG('D', 'S', 'D', ' ')) {
            if (!c->channels)
                return AVERROR_INVALIDDATA;
            c->data_offset = body;
            c->data_end    = body + payload;
            return 0;
        } else if (id == MKBETAG('D', 'S', 'T', ' ')) {
            avpriv_request_sample(s, "compressed DST stream");
            return AVERROR_PATCHWELCOME;
        }

        if (next > frm8_end)
            break;
        if (avio_seek(pb, next, SEEK_SET) < 0)
            break;
    }

    return AVERROR_INVALIDDATA;
}

static int dsdiff_read_packet(AVFormatContext *s, AVPacket *pkt)
{
    DSDIFFContext *c = s->priv_data;
    const int64_t pos = avio_tell(s->pb);
    const int64_t remaining = c->data_end - pos;
    int size, ret;

    if (remaining <= 0)
        return AVERROR_EOF;

    size = (int)FFMIN(remaining, DSDIFF_PACKET_SIZE);
    /* 对齐到整样本（每样本 1 字节/声道），否则 subsequent packet 的声道会错位 */
    size -= size % c->channels;
    if (size <= 0)
        return AVERROR_EOF;

    ret = av_get_packet(s->pb, pkt, size);
    if (ret < 0)
        return ret;
    if (ret == 0)
        return AVERROR_EOF;

    pkt->stream_index = 0;
    pkt->pos          = pos;
    pkt->pts          = (pos - c->data_offset) / c->channels;
    pkt->duration     = ret / c->channels;
    return 0;
}

static int dsdiff_read_seek(AVFormatContext *s, int stream_index,
                            int64_t timestamp, int flags)
{
    DSDIFFContext *c = s->priv_data;
    int64_t pos;

    if (timestamp < 0)
        return -1;
    pos = c->data_offset + timestamp * c->channels;
    if (pos < c->data_offset || pos >= c->data_end)
        return -1;
    /* 落到整样本边界 */
    pos -= (pos - c->data_offset) % c->channels;
    if (avio_seek(s->pb, pos, SEEK_SET) < 0)
        return -1;
    return 0;
}

const FFInputFormat ff_dsdiff_demuxer = {
    .p.name         = "dsdiff",
    .p.long_name    = NULL_IF_CONFIG_SMALL("Direct Stream Digital Interchange File Format (DSDIFF)"),
    .p.extensions   = "dff",
    .p.flags        = AVFMT_GENERIC_INDEX,
    .priv_data_size = sizeof(DSDIFFContext),
    .read_probe     = dsdiff_probe,
    .read_header    = dsdiff_read_header,
    .read_packet    = dsdiff_read_packet,
    .read_seek      = dsdiff_read_seek,
};
