package com.lonx.audiotag.model;

import java.util.HashMap;

/**
 * 一份标签集合（TagLib 的 `Metadata`）。
 *
 * **类名 / 包名 / 构造签名必须与 native 侧一致**：`libtaglib.so` 用
 * `GetMethodID("<init>", "(Ljava/util/HashMap;[Lcom/lonx/audiotag/model/Picture;)V")`
 * 构造它。
 *
 * [propertyMap] 的键是 TagLib 的 PropertyMap 标准键（**全大写**，如 `TITLE` / `ARTIST` /
 * `ALBUM` / `ALBUMARTIST` / `DATE` / `GENRE` / `TRACKNUMBER` / `DISCNUMBER` /
 * `BITS_PER_SAMPLE`），值是该键的多值数组（同一个 tag 可以有多个值，例如合作艺人）。
 */
public final class Metadata {

    private final HashMap<String, String[]> propertyMap;
    private final Picture[] pictures;

    public Metadata(HashMap<String, String[]> propertyMap, Picture[] pictures) {
        this.propertyMap = propertyMap;
        this.pictures = pictures;
    }

    public HashMap<String, String[]> getPropertyMap() {
        return propertyMap;
    }

    /** 内嵌图片数组；`getMetadata(fd, readPictures = false)` 时为空数组 */
    public Picture[] getPictures() {
        return pictures;
    }
}
