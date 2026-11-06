# Temari（Apple Music FairPlay 解密库）— 本项目内的位置与用法

这里放的是 **Temari 的 Rust 源码**（上游：[WorldObservationLog/Temari](https://github.com/WorldObservationLog/Temari)，MIT，
版本 0.3.1），它是安妮音乐盒 Apple Music 播放/下载链路上「SAMPLE-AES 样本解密」的唯一实现。
之前它只存在于 `analysis/temari-src/`（该目录被 `.git/info/exclude` 排除在 git 之外），
现在随仓库一起提交，保证「克隆 → 编译 → 得到可用 .so」这条链不断。

## 目录里有什么

| 路径 | 说明 |
|---|---|
| `src/` | 全部 Rust 源码（`ffi.rs` / `pool.rs` / `rounds.rs` / `rounds_gen.rs` / `stream.rs` / `template.rs`） |
| `Cargo.toml` `Cargo.lock` | crate 定义（`crate-type = ["rlib", "cdylib"]`，零第三方依赖） |
| `rust-toolchain.toml` | 上游锁定的工具链 1.97.1 |
| `LICENSE` `README.zh.md` `README.md` | 上游 MIT 许可与说明（保留原始署名） |
| `build-android.ps1` | 本项目的交叉编译脚本：编出 `.so` 并直接拷进 `app/src/main/jniLibs/arm64-v8a/` |
| `.gitignore` | 上游的，已含 `/target`（本地编译产物不入库） |

**没有**带过来的：`bindings/`（Go / Python / WASM 示例与各平台预编译二进制，本项目用不到）、
`scripts/`、`.github/`、`tools/`、`tests/`。需要的话直接去上游仓库取。

## 怎么用

```powershell
powershell -ExecutionPolicy Bypass -File android-native\native\temari\build-android.ps1
```

（Windows PowerShell 5.1 与 PowerShell 7 都可以跑；脚本是带 UTF-8 BOM 存的。）

已实测：用这份源码重新编译，产物与本仓库里的
`app/src/main/jniLibs/arm64-v8a/libtemari.so` **SHA256 完全一致**
（`B6270F31C449EAF8…`，659 KB），说明入库的源码就是入库的二进制对应的那一份。

脚本做三件事：

1. 指定 NDK clang 作为链接器（`aarch64-linux-android21-clang.cmd`）；
2. `RUSTFLAGS` 里写死两条**必须**的链接参数：
   - `-Wl,-soname,libtemari.so` —— `libannietemari.so` 是通过 **`DT_NEEDED`** 依赖它的
     （CMake 的 `target_link_libraries(annietemari … temari)`），而 NEEDED 里记录的就是
     **SONAME**。少了 SONAME，这个字段会变成构建机上的绝对路径，真机加载直接失败。
     注意：这条链**不是** `dlopen` —— `libannietemari.so` 里的 `tmpl_from_json` /
     `decrypt_sample_ffi` / `tmpl_destroy` 都是 `UND`，由动态链接器在加载期解析；
   - `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384` —— Android 15+ 的 16KB 页对齐要求；
3. 把 `target/aarch64-linux-android/release/libtemari.so` 复制到
   `app/src/main/jniLibs/arm64-v8a/libtemari.so`。

本机 rustup/cargo 是分离安装的（`CARGO_HOME=D:\AnnieMusic\analysis\rust\cargo`、
`RUSTUP_HOME=D:\AnnieMusic\analysis\rust\up`），脚本里已经显式设好这两个变量。

## 与 Kotlin / JNI 的关系

```
TemariDecryptor.kt                    Kotlin 侧入口（create / decryptSample / close）
  └─ libannietemari.so                cpp/native-temari.cpp，JNI 包装（独立 .so，避免连带 anniedsp）
       └─ libtemari.so                本目录编译产物，jdeps 无依赖，纯 Rust std
```

三个 FFI 符号：`tmpl_from_json` / `decrypt_sample_ffi` / `tmpl_destroy`。
