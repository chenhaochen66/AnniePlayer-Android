package com.lonx.audiotag.model;

/**
 * 一张内嵌图片（TagLib 的 `Picture`）。
 *
 * **类名 / 包名 / 构造签名必须与 native 侧一致**：`libtaglib.so` 通过
 * `GetMethodID("<init>", "([BLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")`
 * 构造它。参数顺序：图片字节 → 描述 → 图片类型（如 `Front Cover`）→ MIME。
 */
public final class Picture {

    private final byte[] data;
    private final String description;
    private final String pictureType;
    private final String mimeType;

    public Picture(byte[] data, String description, String pictureType, String mimeType) {
        this.data = data;
        this.description = description;
        this.pictureType = pictureType;
        this.mimeType = mimeType;
    }

    /** 原始图片字节（JPEG / PNG 等，未解码） */
    public byte[] getData() {
        return data;
    }

    public String getDescription() {
        return description;
    }

    /** TagLib 的图片类型名，封面通常是 `Front Cover` */
    public String getPictureType() {
        return pictureType;
    }

    public String getMimeType() {
        return mimeType;
    }
}
