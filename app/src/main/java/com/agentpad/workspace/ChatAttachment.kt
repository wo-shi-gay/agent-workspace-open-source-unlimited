package com.agentpad.workspace

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.view.View
import java.io.File
import android.widget.LinearLayout
import android.widget.TextView

/** Owns attachment picking for ChatActivity. The preview strip is Compose
 *  (AgentComposer chips); this class only holds the selection and feeds it back
 *  through [onChanged]. */
class ChatAttachment(private val activity: Activity) {
    data class Item(val uri: Uri, val name: String, val image: Boolean)
    private val items = mutableListOf<Item>()
    private var imageRequest: ((List<Item>) -> Unit)? = null
    private var fileRequest: ((List<Item>) -> Unit)? = null

    /** Fired whenever the selection changes so the Compose composer can mirror it. */
    var onChanged: (() -> Unit)? = null

    /** Drop one attachment (Compose strip chip). */
    fun remove(item: Item) { items.removeAll { it.uri == item.uri }; onChanged?.invoke() }

    fun setCallbacks(onImages: (List<Item>) -> Unit, onFiles: (List<Item>) -> Unit) {
        imageRequest = onImages; fileRequest = onFiles
    }

    fun showSheet() {
        val dialog = android.app.Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; background = activity.getDrawable(R.drawable.bg_reasoning_sheet)
            setPadding(dp(30), dp(18), dp(30), dp(24))
        }
        sheet.addView(View(activity).apply { background = activity.getDrawable(R.drawable.bg_reasoning_handle) }, LinearLayout.LayoutParams(dp(54), dp(6)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(28) })
        sheet.addView(TextView(activity).apply { text = "添加附件"; textSize = 24f; setTextColor(Color.rgb(16,19,26)); setTypeface(typeface, 1); setPadding(0,0,0,dp(18)) })
        fun option(text: String, click: () -> Unit) = TextView(activity).apply { this.text = text; textSize = 16f; setTextColor(Color.rgb(30,33,42)); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14),0,dp(14),0); background = activity.getDrawable(R.drawable.bg_chat_input_light); setOnClickListener { dialog.dismiss(); click() } }
        sheet.addView(option("▧  选择图片（可多选）") { pick(true) }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })
        sheet.addView(option("＋  选择文件（可多选）") { pick(false) }, LinearLayout.LayoutParams(-1, dp(52)))
        dialog.setContentView(sheet); dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); dialog.window?.setGravity(Gravity.BOTTOM); dialog.show(); dialog.window?.setLayout(-1, (activity.resources.displayMetrics.heightPixels * .42f).toInt())
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return
        val image = requestCode == IMAGE_REQUEST
        val selected = mutableListOf<Item>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) selected += item(clip.getItemAt(i).uri, image) } ?: data.data?.let { selected += item(it, image) }
        selected.forEach { if (items.none { old -> old.uri == it.uri }) items += it }
        onChanged?.invoke(); if (image) imageRequest?.invoke(selected) else fileRequest?.invoke(selected)
    }

    fun current(): List<Item> = items.toList()

    /** Builds OpenAI-compatible content. Files are copied into the session workspace. */
    fun buildContent(workspace: File, prompt: String): org.json.JSONArray {
        val content = org.json.JSONArray()
        content.put(org.json.JSONObject().put("type", "text").put("text", buildString {
            append(prompt)
            items.filter { !it.image }.forEach { append("\n[附件] ${it.name}\n路径：${copyToWorkspace(it, workspace)}") }
            items.filter { it.image }.forEach { append("\n[图片] ${it.name}") }
        }))
        items.filter { it.image }.forEach { item ->
            val bytes = activity.contentResolver.openInputStream(item.uri)?.use { it.readBytes() } ?: ByteArray(0)
            val mime = activity.contentResolver.getType(item.uri) ?: "image/jpeg"
            content.put(org.json.JSONObject().put("type", "image_url").put("image_url", org.json.JSONObject().put("url", "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}")))
        }
        return content
    }

    private fun copyToWorkspace(item: Item, workspace: File): String {
        val dir = File(workspace, "attachments").apply { mkdirs() }
        val safe = item.name.replace(Regex("[/\\\\]"), "_").ifBlank { "attachment" }
        val out = File(dir, safe)
        activity.contentResolver.openInputStream(item.uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out.absolutePath
    }

    fun clear() { items.clear(); onChanged?.invoke() }

    fun pick(image: Boolean) = activity.startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (image) "image/*" else "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        },
        if (image) IMAGE_REQUEST else FILE_REQUEST,
    )

    private fun item(uri: Uri, image: Boolean): Item = Item(uri, queryName(uri) ?: if (image) "图片" else "附件", image)

    private fun queryName(uri: Uri): String? = runCatching {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    companion object { const val IMAGE_REQUEST = 8201; const val FILE_REQUEST = 8202 }
}