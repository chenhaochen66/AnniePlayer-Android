package com.lonx.audiotag;

import com.lonx.audiotag.model.AudioProperties;
import com.lonx.audiotag.model.Metadata;
import com.lonx.audiotag.model.Picture;

import java.util.HashMap;

/**
 * TagLib（C++）的 JNI 门面。
 *
 * `libtaglib.so` 导出的符号是这 6 个**短名**（`Java_com_lonx_audiotag_TagLib_<method>`），
 * 所以这里的包名 / 类名 / 方法名 / 参数顺序**一个字都不能改** —— 改了就变成
 * `UnsatisfiedLinkError`。同理不能加同名的重载方法（短名解析会撞车）。
 *
 * 用法：先 `ParcelFileDescriptor.open` 拿到 fd，再调用；TagLib 自己会用 `dup` + `lseek`，
 * 不会影响调用方的文件位置。每次取不同信息（属性 / 标签 / 图片）建议**各自打开一个 fd**，
 * 避免文件偏移状态的相互影响。
 *
 * 速度：单首 tag 读取在微秒级（TagLib 只解析容器头与 tag 区），是本地曲库扫描的首选；
 * 位深这类 TagLib 的 `AudioProperties` 不提供的字段，才需要退回 ffprobe 补。
 */
public final class TagLib {

    static {
        System.loadLibrary("taglib");
    }

    private TagLib() {
    }

    /**
     * 音频流参数。
     *
     * @param fd        已打开文件的文件描述符（只读即可）
     * @param readStyle 0 = Fast，1 = Average，2 = Accurate（与 TagLib 的
     *                  `AudioProperties::ReadStyle` 一一对应；曲库扫描用 1 足够）
     */
    public static native AudioProperties getAudioProperties(int fd, int readStyle);

    /**
     * 读标签。
     *
     * @param readPictures true 时把内嵌图片一起读出来（会读整张图的字节，慢；列表扫描传 false）
     */
    public static native Metadata getMetadata(int fd, boolean readPictures);

    /** 只读某个键的多值（比读整份 PropertyMap 便宜） */
    public static native String[] getMetadataPropertyValues(int fd, String propertyName);

    /** 只读内嵌图片（封面抽取用） */
    public static native Picture[] getPictures(int fd);

    /** 写回图片（本工程只读不写，保留签名以维持与 so 的对应关系） */
    public static native boolean savePictures(int fd, Picture[] pictures);

    /** 写回标签（同上，本工程不使用） */
    public static native boolean savePropertyMap(int fd, HashMap<String, String[]> propertyMap);
}
