package com.lonx.audiotag.model;

/**
 * 音频流参数（TagLib 的 `AudioProperties`）。
 *
 * **这个类的位置与成员不能改**：它是 `libtaglib.so` 里 JNI 代码用 `FindClass` +
 * `GetMethodID("<init>", "(IIII)V")` 直接构造的对象，包名/类名/构造参数顺序任何一处变动，
 * native 侧都会构造失败（表现为返回 null 或直接 abort）。
 *
 * 字段语义（与 TagLib C++ 一致）：
 * - [length]：时长，**秒**
 * - [bitrate]：平均码率，**kbps**
 * - [sampleRate]：采样率，Hz
 * - [channels]：声道数
 */
public final class AudioProperties {

    private final int length;
    private final int bitrate;
    private final int sampleRate;
    private final int channels;

    public AudioProperties(int length, int bitrate, int sampleRate, int channels) {
        this.length = length;
        this.bitrate = bitrate;
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    /** 时长（秒） */
    public int getLength() {
        return length;
    }

    /** 码率（kbps） */
    public int getBitrate() {
        return bitrate;
    }

    /** 采样率（Hz） */
    public int getSampleRate() {
        return sampleRate;
    }

    /** 声道数 */
    public int getChannels() {
        return channels;
    }
}
