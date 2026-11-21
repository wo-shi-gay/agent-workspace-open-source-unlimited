package com.agentpad.workspace.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 逆向工具集（**原生层**，不经 proot / 不起 Java 进程）。
 *
 * ── 为什么要有它 ──────────────────────────────────────────
 * 原先逆向靠终端里跑 apktool/jadx，链路是：
 *     App → proot → java -jar → fork aapt2
 * proot 要 ptrace 拦截并翻译每个系统调用，这是性能损耗的大头。
 * 这套工具直接在 App 进程内用 DexKit（C++）解析 dex —— 毫秒级。
 *
 * ── 提供的工具 ────────────────────────────────────────────
 *   dexkit_status          自检：so 装了没 / 能加载吗 / 对不上 SHA 吗
 *   dexkit_install         下载安装 so（下载模式）
 *   dexkit_open           打开 APK/dex，返回句柄 id（后续操作用它）
 *   dexkit_close          关闭句柄
 *   dexkit_search_class   用字符串批量反查类（混淆后定位的招牌能力）
 *   dexkit_search_method  用字符串批量反查方法
 *   dexkit_export_dex     把 dex 导出成文件
 *
 * 句柄在进程内用递增 id 管理（不把裸指针暴露给模型）。
 */
object ReverseTools {

    /** 打开的句柄表：id → native handle。 */
    private val handles = linkedMapOf<Int, Long>()
    private var nextId = 1

    /** 已打开项目的元信息：id → 路径（给模型看的）。 */
    private val opened = linkedMapOf<Int, String>()

    fun register(registry: ToolRegistry, ctx: Context) {

        // ---------- 自检 ----------
        registry.register("dexkit_status") {
            val info = DexKit.selfCheck(ctx)
            JSONObject()
                .put("success", true)
                .put("version", DexKitRelease.VERSION)
                .put("url", DexKitRelease.URL_PRIMARY)
                .put("info", JSONObject(info as Map<*, *>))
                .put(
                    "note",
                    "lib_ready=true 且 load_ok=true 才是可用状态。若 configured=false，" +
                        "说明 DexKitRelease.SHA256 还是占位值（云端编完 so 后要回填）。"
                )
                .toString()
        }

        // ---------- 下载安装 so ----------
        registry.register("dexkit_install") {
            val r = DexKitRelease.downloadAndInstall(ctx)
            if (r.isSuccess) {
                val f = r.getOrThrow()
                // 装完顺手加载一次，避免模型还要再调一次
                val loaded = DexKitLoader.load(ctx)
                JSONObject()
                    .put("success", loaded.isSuccess || true)
                    .put("path", f.absolutePath)
                    .put("size", f.length())
                    .put("readonly", !f.canWrite())
                    .put("load_ok", loaded.isSuccess)
                    .put("load_error", loaded.exceptionOrNull()?.message.orEmpty())
                    .toString()
            } else {
                JSONObject().put("success", false)
                    .put("error", r.exceptionOrNull()?.message ?: "下载安装失败")
                    .put("url", DexKitRelease.URL_PRIMARY)
                    .toString()
            }
        }

        // ---------- 打开 ----------
        registry.register("dexkit_open") { args ->
            val raw = args.optString("path").trim()
            // 统一走 ApkInspector 解析：真实路径直接用，旧 /workspace 前缀也仍能识别
            val path = if (raw.isBlank()) null else resolveApk(ctx, raw)
            if (path == null) {
                JSONObject().put("success", false)
                    .put("error", if (raw.isBlank()) "缺少 path" else "找不到文件：$raw")
                    .put("hint", "可以先调 apk_info（什么都不给也行），它返回的 resolved.path 就是真实路径")
                    .toString()
            } else {
                // 确保 so 可用（没装会尝试下载）
                val ens = DexKit.ensure(ctx)
                if (ens.isFailure) {
                    JSONObject().put("success", false)
                        .put("error", "DexKit 不可用：" + ens.exceptionOrNull()?.message)
                        .put("hint", "先调 dexkit_install，或检查 dexkit_status")
                        .toString()
                } else {
                    val realPath = path.absolutePath
                    DexKit.openApk(realPath).fold(
                        onSuccess = { h ->
                            val id = nextId++
                            handles[id] = h
                            opened[id] = realPath
                            val dexN = DexKit.dexCount(h)
                            JSONObject()
                                .put("success", true)
                                .put("handle", id)
                                .put("path", realPath)
                                .put("dex_count", dexN)
                                .put("size", path.length())
                                .toString()
                        },
                        onFailure = { e ->
                            JSONObject().put("success", false)
                                .put("error", e.message ?: "打开失败")
                                .put("path", realPath)
                                .toString()
                        },
                    )
                }
            }
        }

        // ---------- 关闭 ----------
        registry.register("dexkit_close") { args ->
            val id = args.optInt("handle", -1)
            val h = handles.remove(id)
            opened.remove(id)
            if (h == null) {
                JSONObject().put("success", false).put("error", "句柄 $id 不存在（可能已关闭）").toString()
            } else {
                DexKit.close(h)
                JSONObject().put("success", true).put("closed", id).toString()
            }
        }

        // ---------- 用字符串反查类 ----------
        registry.register("dexkit_search_class") { args ->
            withHandle(args) { h ->
                val groups = parseGroups(args) ?: return@withHandle JSONObject()
                    .put("success", false)
                    .put("error", "缺少 queries：应传 {\"名字\":[\"字符串1\",\"字符串2\"]} 形式的对象，" +
                        "或 [{\"key\":\"名字\",\"strings\":[...]}, ...] 形式的数组")
                    .toString()
                val hit = DexKit.batchFindClassesByStrings(h, groups)
                val out = JSONObject()
                groups.keys.forEach { k ->
                    out.put(k, JSONArray(hit[k].orEmpty()))
                }
                JSONObject()
                    .put("success", true)
                    .put("handle", args.optInt("handle"))
                    .put("groups", groups.size)
                    .put("total_strings", groups.values.sumOf { it.size })
                    .put("matched", hit.values.sumOf { it.size })
                    .put("results", out)
                    .put(
                        "note",
                        "每个组返回「同时引用了该组全部字符串」的类描述符（Lcom/a/C; 形式）。" +
                            "DexKit 2.0 对字符串批量查询做过优化，增加查询组不会线性变慢。"
                    )
                    .toString()
            }
        }

        // ---------- 用字符串反查方法 ----------
        registry.register("dexkit_search_method") { args ->
            withHandle(args) { h ->
                val groups = parseGroups(args) ?: return@withHandle JSONObject()
                    .put("success", false)
                    .put("error", "缺少 queries（同 dexkit_search_class）")
                    .toString()
                val hit = DexKit.batchFindMethodsByStrings(h, groups)
                val out = JSONObject()
                groups.keys.forEach { k ->
                    out.put(k, JSONArray(hit[k].orEmpty()))
                }
                JSONObject()
                    .put("success", true)
                    .put("handle", args.optInt("handle"))
                    .put("groups", groups.size)
                    .put("matched", hit.values.sumOf { it.size })
                    .put("results", out)
                    .toString()
            }
        }

        // ---------- 导出 dex ----------
        registry.register("dexkit_export_dex") { args ->
            withHandle(args) { h ->
                val outDir = args.optString("out_dir").trim().ifBlank {
                    File(ctx.filesDir, "dexkit_export").absolutePath
                }
                DexKit.exportDex(h, outDir).fold(
                    onSuccess = {
                        val files = File(outDir).listFiles()?.map { it.name }.orEmpty()
                        JSONObject().put("success", true)
                            .put("out_dir", outDir)
                            .put("files", JSONArray(files))
                            .toString()
                    },
                    onFailure = { e ->
                        JSONObject().put("success", false).put("error", e.message ?: "导出失败").toString()
                    },
                )
            }
        }

        // ---------- 列出已打开 ----------
        registry.register("dexkit_list_open") {
            val arr = JSONArray()
            handles.keys.forEach { id ->
                arr.put(JSONObject().put("handle", id).put("path", opened[id].orEmpty()))
            }
            JSONObject().put("success", true).put("open", arr).put("count", handles.size).toString()
        }

        // ================= 第一步：一条命令看清这个 APK =================

        /**
         * **apk_info —— 全链路的第一步，也是「少调几十次」的关键。**
         *
         * 一次调用同时给出：包名/版本/minSdk/targetSdk、权限、组件（含 exported +
         * intent-filter）、签名 v1/v2/v3 + 证书 DN 与 SHA-256、dex 清单（每个体积）、
         * so 清单、zip 结构统计、APK 自身 SHA-256。
         *
         * 等价于原来在终端里连跑的：
         *   aapt2 dump badging + aapt2 dump xmltree + unzip -l + zipinfo
         *   + grep 权限 + apksigner verify --print-certs
         * 但它是**进程内**的，且路径自动从沙盒映射（/workspace/… 也能认）。
         */
        registry.register("apk_info") { args ->
            val raw = args.optString("path").ifBlank { args.optString("apk") }.ifBlank {
                args.optString("package")
            }
            val hit = ApkInspector.resolve(ctx, raw)
                ?: return@register JSONObject().put("success", false)
                    .put("error", "找不到这个 APK：${raw.ifBlank { "(空)" }}")
                    .put(
                        "hint",
                        "可以直接给这几种：①已安装包名（如 com.agentpad.workspace）" +
                            "②工作区真实路径（如 <workspace>/_apk/Workspace.apk）；旧的 /workspace/x.apk 前缀仍可识别" +
                            "③真机绝对路径（/storage/emulated/0/Download/x.apk）；" +
                            "什么都不给 = 在工作区/下载目录里自动找最新的 .apk"
                    )
                    .put("searched_roots", JSONArray(listOf(
                        "/storage/emulated/0/Download",
                        "getExternalFilesDir()/workspaces/*",
                        "filesDir/rev",
                    )))
                    .toString()

            val info = ApkInspector.inspect(ctx, hit.file)
            info.put("resolved", JSONObject().put("path", hit.file.absolutePath).put("how", hit.how))
                .put("path_note", "沙盒里的路径与这里的 path 不是同一套；后续工具可直接用 resolved.path，也可以继续给原沙盒路径（会自动映射）")
                .toString()
        }

        // ================= 完整逆向链：解包 → 反编译 → 改 → 回编译 → 打包 → 签名 =================

        /** 解包 APK（纯 zip，资源保持二进制原样）。 */
        registry.register("apk_unzip") { args ->
            val apk = resolveApk(ctx, args.optString("apk"))
                ?: return@register JSONObject().put("success", false)
                    .put("error", "APK 不存在：${args.optString("apk")}")
                    .put("hint", "先调 apk_info（path 可留空/给包名），它会一并返回可用的真实路径").toString()
            val outDir = File(args.optString("out_dir").trim().ifBlank {
                File(ctx.filesDir, "rev/${apk.nameWithoutExtension}").absolutePath
            })
            ApkRepack.unzip(apk, outDir).fold(
                onSuccess = { n ->
                    JSONObject().put("success", true).put("out_dir", outDir.absolutePath)
                        .put("entries", n)
                        .put("dex_entries", JSONArray(ApkRepack.dexEntries(apk)))
                        .put("next", "用 dex_to_smali 把 ${apk.name} 反汇编成 smali 文本")
                        .toString()
                },
                onFailure = { e -> JSONObject().put("success", false).put("error", e.message).toString() },
            )
        }

        /** dex/apk → smali 文本目录。 */
        registry.register("dex_to_smali") { args ->
            val src = File(args.optString("input").trim())
            if (!src.isFile) return@register JSONObject().put("success", false)
                .put("error", "输入不存在：${src.absolutePath}").toString()
            val outDir = File(args.optString("out_dir").trim().ifBlank {
                File(ctx.filesDir, "rev/${src.nameWithoutExtension}_smali").absolutePath
            })
            val api = args.optInt("api_level", SmaliToolchain.DEFAULT_API)
            SmaliToolchain.dexToSmali(src, outDir, api).fold(
                onSuccess = { n ->
                    JSONObject().put("success", true)
                        .put("smali_dir", outDir.absolutePath)
                        .put("files", n)
                        .put("api_level", api)
                        .put("next", "用 read_file 读 .smali，edit_file 改，再用 smali_to_dex 汇编回去")
                        .toString()
                },
                onFailure = { e ->
                    JSONObject().put("success", false).put("error", e.message)
                        .put("hint", "APK 含多个 dex 时请先用 apk_unzip 解出 classes*.dex 再逐个转")
                        .toString()
                },
            )
        }

        /**
         * smali 目录 → dex。
         * ⚠ 大 dex（>5MB）走这条路会 OOM：Smali.assemble 要把整棵语法树放堆里。
         *   改单个类请用 dex_patch_class（只编译改动的类再合并）。
         */
        registry.register("smali_to_dex") { args ->
            val dir = File(args.optString("smali_dir").trim())
            if (!dir.exists()) return@register JSONObject().put("success", false)
                .put("error", "smali 目录不存在：${dir.absolutePath}").toString()
            val outDex = File(args.optString("out_dex").trim().ifBlank {
                File(ctx.filesDir, "rev/${dir.name}.dex").absolutePath
            })
            val api = args.optInt("api_level", SmaliToolchain.DEFAULT_API)
            SmaliToolchain.smaliDirToDex(dir, outDex, api).fold(
                onSuccess = { size ->
                    JSONObject().put("success", true).put("dex", outDex.absolutePath)
                        .put("size", size).toString()
                },
                onFailure = { e ->
                    JSONObject().put("success", false).put("error", e.message)
                        .put("hint", "smali 语法错误时 verboseErrors 会把出错行打出来，看 error 里的详情")
                        .toString()
                },
            )
        }

        /**
         * **低内存快路径（推荐）**：只编译改动过的 smali 文件，再合并回原 dex。
         *
         * 为什么要有它：把整个 smali 树交给 Smali.assemble，内存是 dex 体积的
         * 20~40 倍 —— 12MB 的 dex 要 300~500MB，直接 OOM（真机实测
         * target footprint 402653184 = 384MB）。而这里第 ① 步只编译几个类
         * （几十 KB），第 ② 步用 dexlib2 把类替换进原 dex，峰值只有原 dex 的 3~5 倍。
         */
        registry.register("dex_patch_class") { args ->
            val baseDex = File(args.optString("base_dex").trim())
            if (!baseDex.isFile) return@register JSONObject().put("success", false)
                .put("error", "base_dex 不存在：${baseDex.absolutePath}")
                .put("hint", "先用 apk_unzip 解出 classes.dex").toString()

            // 抓 smali_files：支持数组，或单个 smali_dir 下的某个类
            val files = ArrayList<File>()
            args.optJSONArray("smali_files")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i).trim()
                    if (p.isNotBlank()) files.add(File(p))
                }
            }
            if (files.isEmpty()) {
                val root = args.optString("smali_dir").trim()
                val cls = args.optString("class").trim()
                if (root.isNotBlank() && cls.isNotBlank()) {
                    val rel = SmaliToolchain.toDescriptor(cls).removePrefix("L").removeSuffix(";")
                    files.add(File(root, "$rel.smali"))
                }
            }
            if (files.isEmpty()) return@register JSONObject().put("success", false)
                .put("error", "没有要编译的 smali。给 smali_files 数组，或 smali_dir + class")
                .toString()
            files.forEach {
                if (!it.isFile) return@register JSONObject().put("success", false)
                    .put("error", "smali 文件不存在：${it.absolutePath}").toString()
            }

            val outDex = File(args.optString("out_dex").trim().ifBlank {
                File(baseDex.parentFile, baseDex.nameWithoutExtension + "_patched.dex").absolutePath
            })
            val api = args.optInt("api_level", SmaliToolchain.DEFAULT_API)
            SmaliToolchain.patchClassesBySmali(baseDex, files, outDex, api).fold(
                onSuccess = { (size, classes) ->
                    JSONObject().put("success", true)
                        .put("dex", outDex.absolutePath)
                        .put("size", size)
                        .put("patched_classes", classes)
                        .put("hint", "接下来用 apk_replace_dex_and_sign 把 dex 塞回 APK 并签名")
                        .toString()
                },
                onFailure = { e ->
                    JSONObject().put("success", false).put("error", e.message)
                        .put("note", "这条路仍然失败的话，说明目标 dex 本身过大，考虑先把 APK 拆成多 dex 处理")
                        .toString()
                },
            )
        }

        /** 读一个类的 smali 源码。 */
        registry.register("smali_read") { args ->
            val root = File(args.optString("smali_dir").trim())
            val cls = args.optString("class").trim()
            if (cls.isBlank()) return@register JSONObject().put("success", false)
                .put("error", "缺少 class，例如 com.a.B").toString()
            SmaliToolchain.readSmali(root, cls).fold(
                onSuccess = { text ->
                    JSONObject().put("success", true)
                        .put("class", SmaliToolchain.toDescriptor(cls))
                        .put("chars", text.length)
                        .put("content", text.take(60000))
                        .put("truncated", text.length > 60000)
                        .toString()
                },
                onFailure = { e -> JSONObject().put("success", false).put("error", e.message).toString() },
            )
        }

        /** 写回一个类的 smali 源码（改完用这个）。 */
        registry.register("smali_write") { args ->
            val root = File(args.optString("smali_dir").trim())
            val cls = args.optString("class").trim()
            val text = args.optString("content")
            if (cls.isBlank() || text.isBlank()) return@register JSONObject().put("success", false)
                .put("error", "需要 class + content").toString()
            SmaliToolchain.writeSmali(root, cls, text).fold(
                onSuccess = { f ->
                    JSONObject().put("success", true).put("path", f.absolutePath)
                        .put("bytes", f.length()).toString()
                },
                onFailure = { e -> JSONObject().put("success", false).put("error", e.message).toString() },
            )
        }

        /** 重打包（目录 → APK，可替换 entry）。 */
        registry.register("apk_zip") { args ->
            val dir = File(args.optString("dir").trim())
            if (!dir.isDirectory) return@register JSONObject().put("success", false)
                .put("error", "目录不存在：${dir.absolutePath}").toString()
            val out = File(args.optString("out_apk").trim().ifBlank {
                File(ctx.filesDir, "rev/${dir.name}-unsigned.apk").absolutePath
            })
            val replace = linkedMapOf<String, File>()
            args.optJSONObject("replace")?.let { o ->
                o.keys().forEach { k ->
                    val p = o.optString(k).trim()
                    if (p.isNotBlank()) replace[k] = File(p)
                }
            }
            ApkRepack.zip(dir, out, replace).fold(
                onSuccess = { size ->
                    JSONObject().put("success", true).put("apk", out.absolutePath)
                        .put("size", size)
                        .put("note", "这是未签名包，装不上，必须再 apk_sign")
                        .toString()
                },
                onFailure = { e -> JSONObject().put("success", false).put("error", e.message).toString() },
            )
        }

        /** 签名（v1+v2+v3）。 */
        registry.register("apk_sign") { args ->
            val inApk = File(args.optString("apk").trim())
            val ks = File(args.optString("keystore").trim())
            val alias = args.optString("alias").trim()
            val storePass = args.optString("store_password")
            val keyPass = args.optString("key_password").ifBlank { storePass }
            if (!inApk.isFile) return@register JSONObject().put("success", false)
                .put("error", "APK 不存在：${inApk.absolutePath}").toString()
            if (!ks.isFile) return@register JSONObject().put("success", false)
                .put("error", "keystore 不存在：${ks.absolutePath}").toString()
            if (alias.isBlank()) return@register JSONObject().put("success", false)
                .put("error", "缺少 alias").toString()
            val out = File(args.optString("out_apk").trim().ifBlank {
                inApk.parentFile?.let { File(it, inApk.nameWithoutExtension + "-signed.apk").absolutePath }
                    ?: File(ctx.filesDir, "rev/signed.apk").absolutePath
            })
            ApkRepack.sign(inApk, out, ks, storePass, alias, keyPass).fold(
                onSuccess = { size ->
                    JSONObject().put("success", true).put("apk", out.absolutePath)
                        .put("size", size)
                        .put("note", "已用 v1+v2+v3 重签；签名与原应用不同，无法覆盖安装原版")
                        .toString()
                },
                onFailure = { e -> JSONObject().put("success", false).put("error", e.message).toString() },
            )
        }

        /** 一站式：把新 dex 塞回原 APK 并签名。 */
        registry.register("apk_replace_dex_and_sign") { args ->
            val apk = File(args.optString("apk").trim())
            val dex = File(args.optString("dex").trim())
            val ks = File(args.optString("keystore").trim())
            val alias = args.optString("alias").trim()
            val storePass = args.optString("store_password")
            if (!apk.isFile || !dex.isFile || !ks.isFile || alias.isBlank()) {
                return@register JSONObject().put("success", false)
                    .put("error", "需要 apk + dex + keystore + alias(+password)").toString()
            }
            val out = File(args.optString("out_apk").trim().ifBlank {
                File(ctx.filesDir, "rev/${apk.nameWithoutExtension}-patched.apk").absolutePath
            })
            val work = File(ctx.filesDir, "rev/work_${System.currentTimeMillis()}")
            ApkRepack.replaceDexAndSign(
                originalApk = apk,
                newDex = dex,
                outApk = out,
                keystore = ks,
                storePass = storePass,
                alias = alias,
                keyPass = args.optString("key_password").ifBlank { storePass },
                dexEntryName = args.optString("dex_entry").ifBlank { "classes.dex" },
                workDir = work,
            ).fold(
                onSuccess = { size ->
                    work.deleteRecursively()
                    JSONObject().put("success", true).put("apk", out.absolutePath)
                        .put("size", size).toString()
                },
                onFailure = { e ->
                    work.deleteRecursively()
                    JSONObject().put("success", false).put("error", e.message).toString()
                },
            )
        }
    }

    /**
     * 统一路径解析：模型给的多半是**沙盒里的路径**（/workspace/x.apk、/sdcard/x.apk），
     * 而本工具集跑在 App 进程里，两边挂载点不同。这里统一交给 [ApkInspector.resolve]
     * 做反映射；解析失败再退回原样判断，避免误伤真实绝对路径。
     *
     * 存在的意义：一条路径写错就白跑一次工具调用，而模型往往要重试六七次才摸对 ——
     * 这正是「59 次调用」里最贵的一段。
     */
    private fun resolveApk(ctx: Context, raw: String): File? {
        val t = raw.trim()
        if (t.isBlank()) return null
        ApkInspector.resolve(ctx, t)?.let { return it.file }
        val f = File(t)
        return if (f.isFile && f.length() > 0) f.absoluteFile else null
    }

    /** 统一的句柄校验 + 执行包装。 */
    private inline fun withHandle(args: JSONObject, block: (Long) -> String): String {
        val id = args.optInt("handle", -1)
        val h = handles[id]
            ?: return JSONObject().put("success", false)
                .put("error", "句柄 $id 不存在。先调 dexkit_open 打开一个 APK/dex。")
                .put("open_handles", JSONArray(handles.keys.toList()))
                .toString()
        return block(h)
    }

    /**
     * 解析模型给的查询组。支持两种写法：
     *   {"登录逻辑": ["login", "signin"], "校验": ["verify"]}
     *   [{"key":"登录逻辑","strings":["login","signin"]}, ...]
     */
    private fun parseGroups(args: JSONObject): LinkedHashMap<String, List<String>>? {
        val out = linkedMapOf<String, List<String>>()

        args.optJSONObject("queries")?.let { obj ->
            obj.keys().forEach { k ->
                val arr = obj.optJSONArray(k) ?: return@forEach
                val list = (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
                if (list.isNotEmpty()) out[k] = list
            }
        }

        if (out.isEmpty()) {
            args.optJSONArray("queries")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val k = o.optString("key").ifBlank { "group$i" }
                    val sa = o.optJSONArray("strings") ?: continue
                    val list = (0 until sa.length()).mapNotNull { sa.optString(it).ifBlank { null } }
                    if (list.isNotEmpty()) out[k] = list
                }
            }
        }

        // 也允许直接传 queries 为「逗号分隔的字符串」（单组简写）
        if (out.isEmpty()) {
            val simple = args.optString("queries").trim()
            if (simple.contains(',')) {
                out["q1"] = simple.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

        return out.ifEmpty { null }
    }

    // ============ 工具规格（给模型的描述 + JSON Schema） ============

    /** 返回 null 表示不是本模块的工具。 */
    fun spec(name: String): ToolSpec? = when (name) {
        // ⚠ 逆向类任务的第一条调用永远应该是它，别先用 terminal 找文件
        "apk_info" -> ToolSpec(
            name,
            "**CALL THIS FIRST for any APK question. One call returns everything** — do NOT use the terminal, " +
                "aapt2, apksigner, unzip -l, zipinfo or grep for this. Runs fully in-process (no proot).\n" +
                "Returns in a single response: package name, versionName/versionCode, minSdk/targetSdk, " +
                "permissions[], components{activities/services/receivers/providers} each with exported + intent_filters, " +
                "launcher activity, application flags (debuggable/allowBackup/usesCleartextTraffic/extractNativeLibs), " +
                "signature{verified, v1, v2, v3, certificates[{subject, issuer, sha256, validity}]}, " +
                "dex[] with per-file sizes, native libs[], zip stats (entry counts, compressed/uncompressed, " +
                "resources.arsc STORED check) and the APK's own SHA-256.\n" +
                "PATH IS AUTO-RESOLVED: pass the REAL workspace path (an old /workspace/X.apk prefix is still translated for compatibility), " +
                "/sdcard/... , a real /storage/emulated/0/... path, an installed package name (com.foo.bar), " +
                "or nothing at all (it scans the workspace and Download dir). The reply contains resolved.path — " +
                "reuse that for follow-up tools. Never hunt for the file with find/ls.",
            "{\"type\":\"object\",\"properties\":{" +
                "\"path\":{\"type\":\"string\",\"description\":\"Real workspace path (e.g. .../workspaces/<id>/_apk/x.apk), or package name. Optional.\"}," +
                "\"package\":{\"type\":\"string\",\"description\":\"Alternative: installed package name\"}" +
                "}}",
        )

        "dexkit_status" -> ToolSpec(
            name,
            "Check whether the native DexKit engine is ready: is the .so installed in the app's private dir, did the SHA-256 check pass, did System.load succeed. " +
                "Call this FIRST when any dexkit_* tool reports an error. The engine is downloaded on demand (not bundled in the APK).",
            "{\"type\":\"object\",\"properties\":{}}",
        )

        "dexkit_install" -> ToolSpec(
            name,
            "Download and install the native DexKit engine (.so) into the app's private directory, verify its SHA-256, mark it read-only, then load it. " +
                "Use this when dexkit_status says lib_ready=false. Requires network access.",
            "{\"type\":\"object\",\"properties\":{}}",
        )

        "dexkit_open" -> ToolSpec(
            name,
            "Open an APK or .dex file with the native DexKit engine and get a handle id for the other dexkit_* tools. " +
                "This runs entirely in-process (no proot, no java) so it is fast. Remember to dexkit_close when done.",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Absolute path to an APK or .dex file\"}},\"required\":[\"path\"]}",
        )

        "dexkit_close" -> ToolSpec(
            name,
            "Close a handle opened by dexkit_open and free its native resources.",
            "{\"type\":\"object\",\"properties\":{\"handle\":{\"type\":\"integer\",\"description\":\"Handle id returned by dexkit_open\"}},\"required\":[\"handle\"]}",
        )

        "dexkit_search_class" -> ToolSpec(
            name,
            "Find obfuscated classes by the STRINGS they reference. This is DexKit's flagship ability: when class names are stripped by obfuscation/packing, " +
                "match on stable strings instead (URLs, error messages, log tags, preference keys, JSON field names). " +
                "Pass several query groups at once — DexKit's batch search does NOT get linearly slower as you add groups. " +
                "Returns, per group, the dex descriptors (Lcom/a/C; form) of classes that reference ALL strings in that group.",
            "{\"type\":\"object\",\"properties\":{" +
                "\"handle\":{\"type\":\"integer\",\"description\":\"Handle from dexkit_open\"}," +
                "\"queries\":{\"type\":\"object\",\"description\":\"Group name -> array of strings that class must reference. e.g. {\\\"login\\\":[\\\"signin\\\",\\\"/api/user/login\\\"]}\"}" +
                "},\"required\":[\"handle\",\"queries\"]}",
        )

        "dexkit_search_method" -> ToolSpec(
            name,
            "Like dexkit_search_class but returns METHODS whose code references the given strings (dex descriptor form). " +
                "Use it to pin down the exact method behind an obfuscated name — e.g. which method builds the login request.",
            "{\"type\":\"object\",\"properties\":{" +
                "\"handle\":{\"type\":\"integer\",\"description\":\"Handle from dexkit_open\"}," +
                "\"queries\":{\"type\":\"object\",\"description\":\"Group name -> array of strings the method must reference\"}" +
                "},\"required\":[\"handle\",\"queries\"]}",
        )

        "dexkit_export_dex" -> ToolSpec(
            name,
            "Export the dex files opened by dexkit_open to a directory (useful after a packer is removed, to get plain .dex you can inspect further).",
            "{\"type\":\"object\",\"properties\":{" +
                "\"handle\":{\"type\":\"integer\",\"description\":\"Handle from dexkit_open\"}," +
                "\"out_dir\":{\"type\":\"string\",\"description\":\"Optional output directory. Defaults to app-private dir.\"}" +
                "},\"required\":[\"handle\"]}",
        )

        "dexkit_list_open" -> ToolSpec(
            name,
            "List the APK/dex files currently open with the native engine, with their handle ids.",
            "{\"type\":\"object\",\"properties\":{}}",
        )

        // ---------- 完整逆向链 ----------

        "apk_unzip" -> ToolSpec(
            name,
            "Unzip an APK to a directory (plain zip extraction; resources stay as raw binary, nothing is recompiled). " +
                "This is step 1 of the patch-and-rebuild flow: apk_unzip -> dex_to_smali -> edit smali -> smali_to_dex -> apk_zip -> apk_sign.",
            "{\"type\":\"object\",\"properties\":{\"apk\":{\"type\":\"string\",\"description\":\"Absolute path to the APK\"},\"out_dir\":{\"type\":\"string\",\"description\":\"Optional output directory\"}},\"required\":[\"apk\"]}",
        )

        "dex_to_smali" -> ToolSpec(
            name,
            "Disassemble a dex (or the first dex inside an APK) into readable smali text files, using the native baksmali library. " +
                "After this you can read and edit the .smali files. Keeps debug info (line numbers, local names) so the code is readable.",
            "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\",\"description\":\"Path to a .dex or .apk\"},\"out_dir\":{\"type\":\"string\",\"description\":\"Output smali directory\"},\"api_level\":{\"type\":\"integer\",\"description\":\"Dex opcodes API level, default 30\"}},\"required\":[\"input\"]}",
        )

        "smali_to_dex" -> ToolSpec(
            name,
            "Assemble a smali directory back into a .dex. Use after editing smali text. " +
                "Compile errors are reported with line details (verboseErrors is on).",
            "{\"type\":\"object\",\"properties\":{\"smali_dir\":{\"type\":\"string\",\"description\":\"Smali root directory\"},\"out_dex\":{\"type\":\"string\",\"description\":\"Output .dex path\"},\"api_level\":{\"type\":\"integer\",\"description\":\"Default 30\"}},\"required\":[\"smali_dir\"]}",
        )

        "smali_read" -> ToolSpec(
            name,
            "Read one class's smali source text from a disassembled directory. Class can be given as com.a.B or Lcom/a/B;.",
            "{\"type\":\"object\",\"properties\":{\"smali_dir\":{\"type\":\"string\"},\"class\":{\"type\":\"string\",\"description\":\"e.g. com.a.B\"}},\"required\":[\"smali_dir\",\"class\"]}",
        )

        "smali_write" -> ToolSpec(
            name,
            "Write back one class's smali source (use after editing). This overwrites the .smali file for that class.",
            "{\"type\":\"object\",\"properties\":{\"smali_dir\":{\"type\":\"string\"},\"class\":{\"type\":\"string\"},\"content\":{\"type\":\"string\",\"description\":\"Full new smali text\"}},\"required\":[\"smali_dir\",\"class\",\"content\"]}",
        )

        "dex_patch_class" -> ToolSpec(
            name,
            "**PREFERRED way to apply a smali edit on a large dex.** Compiles ONLY the changed .smali file(s) into a tiny dex, then merges those classes back into the original dex with dexlib2. " +
                "Why: compiling the WHOLE smali tree needs 20-40x the dex size in heap (a 12MB dex → 300-500MB → OutOfMemoryError, the app heap cap is 384MB). " +
                "This path only peaks at 3-5x the original dex size, so it survives where smali_to_dex dies. " +
                "Give either smali_files[] (a list of changed .smali paths) or smali_dir + class. Same-named classes in the base dex get replaced; extra ones are appended.",
            "{\"type\":\"object\",\"properties\":{" +
                "\"base_dex\":{\"type\":\"string\",\"description\":\"Original dex (from apk_unzip)\"}," +
                "\"smali_files\":{\"type\":\"array\",\"description\":\"Changed .smali file paths\",\"items\":{\"type\":\"string\"}}," +
                "\"smali_dir\":{\"type\":\"string\",\"description\":\"Alternative to smali_files: smali root\"}," +
                "\"class\":{\"type\":\"string\",\"description\":\"Used with smali_dir, e.g. com.a.B\"}," +
                "\"out_dex\":{\"type\":\"string\"},\"api_level\":{\"type\":\"integer\",\"description\":\"Default 30\"}" +
                "},\"required\":[\"base_dex\"]}",
        )

        "apk_zip" -> ToolSpec(
            name,
            "Repack a directory into an APK (zip). You can replace specific entries (e.g. put the rebuilt classes.dex back) via `replace`. " +
                "resources.arsc / lib/* are written STORED (uncompressed) as Android requires. The result is UNSIGNED and cannot be installed yet.",
            "{\"type\":\"object\",\"properties\":{\"dir\":{\"type\":\"string\",\"description\":\"Unpacked APK directory\"},\"out_apk\":{\"type\":\"string\"},\"replace\":{\"type\":\"object\",\"description\":\"entryName -> local file path, e.g. {\\\"classes.dex\\\":\\\"/path/new.dex\\\"}\"}},\"required\":[\"dir\"]}",
        )

        "apk_sign" -> ToolSpec(
            name,
            "Sign an APK with a keystore (v1+v2+v3 via Google's apksig). Without this the rebuilt APK cannot be installed. " +
                "NOTE: the new signature differs from the original app's, so it cannot be installed OVER the original — only as a separate app (or after renaming the package).",
            "{\"type\":\"object\",\"properties\":{\"apk\":{\"type\":\"string\"},\"keystore\":{\"type\":\"string\",\"description\":\"Path to .jks/.p12\"},\"alias\":{\"type\":\"string\"},\"store_password\":{\"type\":\"string\"},\"key_password\":{\"type\":\"string\"},\"out_apk\":{\"type\":\"string\"}},\"required\":[\"apk\",\"keystore\",\"alias\",\"store_password\"]}",
        )

        "apk_replace_dex_and_sign" -> ToolSpec(
            name,
            "One-shot: take the original APK, swap in your rebuilt dex, repack and sign it. " +
                "This is the quickest path once you have an edited+assembled .dex.",
            "{\"type\":\"object\",\"properties\":{\"apk\":{\"type\":\"string\",\"description\":\"Original APK\"},\"dex\":{\"type\":\"string\",\"description\":\"Rebuilt .dex\"},\"keystore\":{\"type\":\"string\"},\"alias\":{\"type\":\"string\"},\"store_password\":{\"type\":\"string\"},\"key_password\":{\"type\":\"string\"},\"dex_entry\":{\"type\":\"string\",\"description\":\"Default classes.dex\"},\"out_apk\":{\"type\":\"string\"}},\"required\":[\"apk\",\"dex\",\"keystore\",\"alias\",\"store_password\"]}",
        )

        else -> null
    }
}