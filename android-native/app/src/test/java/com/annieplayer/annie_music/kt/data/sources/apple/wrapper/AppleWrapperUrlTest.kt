package com.annieplayer.annie_music.kt.data.sources.apple.wrapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wrapper 地址解析（纯函数）。
 *
 * 为什么值得测：用户粘贴的地址**就是**认证凭据（`https://<token>@host`）。
 * 解析错了不会报错，只会表现成「连接失败 / 401」，用户完全看不出是地址被人为改过。
 * 这些用例都是从真实输入里抽出来的形态。
 */
class AppleWrapperUrlTest {

    @Test
    fun token_in_userinfo_becomes_basic_payload_with_empty_password() {
        // 服务方给的就是这种：URL 里塞 token，密码为空 → Basic 载荷必须带冒号
        val ep = parseWrapperUrl("https://qXut5osAOzeJN5dG-YXxq8-5@wm.wol.moe")
        assertNotNull(ep)
        assertEquals("https://wm.wol.moe", ep!!.base)
        assertEquals("qXut5osAOzeJN5dG-YXxq8-5:", ep.basicPayload)
    }

    @Test
    fun user_and_password_are_kept_verbatim() {
        val ep = parseWrapperUrl("http://annie:s3cret@192.168.1.7:12341")
        assertEquals("http://192.168.1.7:12341", ep!!.base)
        assertEquals("annie:s3cret", ep.basicPayload)
    }

    @Test
    fun host_only_gets_http_scheme() {
        // 本机 wrapper-lite 的写法：没有 scheme
        val ep = parseWrapperUrl("127.0.0.1:12341")
        assertEquals("http://127.0.0.1:12341", ep!!.base)
        assertNull(ep.basicPayload)
    }

    @Test
    fun trailing_slash_and_query_fragment_are_dropped() {
        // 从浏览器地址栏复制来的尾巴不能拼进请求路径
        val ep = parseWrapperUrl("https://token@host.tld/?a=1#frag")
        assertEquals("https://host.tld", ep!!.base)
    }

    @Test
    fun reverse_proxy_sub_path_is_preserved() {
        // 挂在反代子路径下的实例：base 要带上 /apple
        val ep = parseWrapperUrl("https://host.tld/apple/")
        assertEquals("https://host.tld/apple", ep!!.base)
    }

    @Test
    fun password_may_contain_at_sign() {
        // 只按**最后一个** '@' 切分，否则密码里的 '@' 会被当成主机分隔符
        val ep = parseWrapperUrl("https://user:p@ss@wrapper.example.com")
        assertEquals("https://wrapper.example.com", ep!!.base)
        assertEquals("user:p@ss", ep.basicPayload)
    }

    @Test
    fun percent_encoded_userinfo_is_decoded() {
        val ep = parseWrapperUrl("https://a%40b:pw@host.tld")
        assertEquals("a@b:pw", ep!!.basicPayload)
    }

    @Test
    fun garbage_is_rejected() {
        assertNull(parseWrapperUrl(""))
        assertNull(parseWrapperUrl("   "))
        // 只支持 http/https（别让人以为 ftp:// 能跑）
        assertNull(parseWrapperUrl("ftp://host.tld"))
        // 没有主机
        assertNull(parseWrapperUrl("https://"))
        assertNull(parseWrapperUrl("https:///path"))
        // 主机里有非法字符（比如误粘贴了整句话）
        assertNull(parseWrapperUrl("https://host tld"))
    }
}
