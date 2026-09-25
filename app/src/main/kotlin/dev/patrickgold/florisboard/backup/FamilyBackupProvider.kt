package dev.patrickgold.florisboard.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * عقد «باكأب أمان» (AmanBackup v1) لكلام بورد: يجمع ما يخصّ المستخدم من ملفاته مباشرةً —
 * تفضيلات jetpref، وقاموس المستخدم وقاعدة التعلّم (الكلمات المتعلَّمة والـn-grams والإحصاءات)،
 * وامتدادات الثيمات/اللوحات المثبّتة — مضغوطةً في zip، بلا مسّ واجهته أو منتقي الملفات.
 *
 * الاستعادة تكتب الملفات مكانها ثم تُنهي العملية، فتُفتح القواعد نظيفةً عند التشغيل التالي.
 */
class FamilyBackupProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun ctx(): Context = context!!.applicationContext

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!callerIsAmanStore()) return null
        return when (method) {
            "describe" -> Bundle().apply { putString("format", FORMAT) }
            "export" -> {
                val blob = (try { entriesWritten = 0; exportZip() } catch (e: Exception) { null }) ?: return null
                Bundle().apply {
                    putParcelable("pfd", pumpToPipe(blob))
                    putString("format", FORMAT)
                }
            }
            "import" -> {
                if (extras?.getString("format") != null && extras.getString("format") != FORMAT) {
                    return Bundle().apply { putBoolean("ok", false) }
                }
                val pfd = extras?.getParcelable<ParcelFileDescriptor>("pfd")
                    ?: return Bundle().apply { putBoolean("ok", false) }
                val ok = try {
                    val blob = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
                    importZip(blob)
                } catch (e: Exception) { false }
                Bundle().apply { putBoolean("ok", ok) }
            }
            else -> null
        }
    }

    private fun dataDir(): File = File(ctx().applicationInfo.dataDir)
    private fun dbDir(): File = File(dataDir(), "databases")
    private fun prefsDir(): File = File(dataDir(), "shared_prefs")

    private fun exportZip(): ByteArray? {
        val bytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zip ->
                // قواعد المستخدم: قاموسه المتعلَّم وقاعدة التعلّم والإحصاءات (بما فيها ملفات wal/shm).
                dbDir().listFiles()?.filter { it.isFile && USER_DBS.any { p -> it.name.startsWith(p) } }
                    ?.forEach { f -> zip.putFile("databases/${f.name}", f) }
                // تفضيلات jetpref وأي تفضيلات نظامٍ للتطبيق.
                File(ctx().filesDir, JETPREF_DIR).listFiles()?.filter { it.isFile }
                    ?.forEach { f -> zip.putFile("files/$JETPREF_DIR/${f.name}", f) }
                prefsDir().listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }
                    ?.forEach { f -> zip.putFile("prefs/${f.name}", f) }
                // امتدادات المستخدم المثبّتة (ثيمات/لوحات/حزم لغات).
                for (p in EXT_DIRS) {
                    File(ctx().filesDir, p).walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = f.relativeTo(ctx().filesDir).path.replace(File.separatorChar, '/')
                        zip.putFile("files/$rel", f)
                    }
                }
            }
            bos.toByteArray()
        }
        // لوحةٌ لم تُستعمل بعد (لا قواميس ولا تفضيلات) لا تُدرَج فارغةً — يتخطّاها المتجر بصراحة.
        return if (entriesWritten == 0) null else bytes
    }

    private var entriesWritten = 0
    private fun ZipOutputStream.putFile(entryName: String, file: File) {
        putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
        entriesWritten++
    }

    private fun importZip(blob: ByteArray): Boolean {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(blob)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) entries[e.name] = zip.readBytes()
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        if (entries.isEmpty()) return false
        // امسح قواعد المستخدم القديمة (بما فيها wal/shm) كي لا تُدمج ببقايا سابقة.
        dbDir().listFiles()?.filter { it.isFile && USER_DBS.any { p -> it.name.startsWith(p) } }
            ?.forEach { it.delete() }
        for ((name, bytes) in entries) {
            val target = when {
                name.startsWith("databases/") -> File(dbDir(), name.removePrefix("databases/"))
                name.startsWith("prefs/") -> File(prefsDir(), name.removePrefix("prefs/"))
                name.startsWith("files/") -> File(ctx().filesDir, name.removePrefix("files/"))
                else -> continue
            }
            // حارس اجتياز المسارات: لا نكتب خارج مجلدات التطبيق مهما كان اسم المدخل.
            val root = target.parentFile ?: continue
            if (!root.canonicalPath.startsWith(dataDir().canonicalPath)) continue
            root.mkdirs()
            target.outputStream().use { it.write(bytes) }
        }
        android.os.Handler(ctx().mainLooper).postDelayed({ Runtime.getRuntime().exit(0) }, 400)
        return true
    }

    private fun pumpToPipe(data: ByteArray): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        thread(name = "kalamboard-backup-pump") {
            try {
                FileOutputStream(pipe[1].fileDescriptor).use { it.write(data); it.flush() }
                pipe[1].close()
            } catch (_: Exception) {
                runCatching { pipe[1].closeWithError("pump failed") }
            }
        }
        return pipe[0]
    }

    private fun callerIsAmanStore(): Boolean {
        val caller = callingPackage ?: return false
        if (caller != STORE_PACKAGE) return false
        val c = context ?: return false
        val sig = try {
            val pm = c.packageManager
            val cert: ByteArray = if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(caller, PackageManager.GET_SIGNING_CERTIFICATES)
                val si = info.signingInfo ?: return false
                val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                sigs?.firstOrNull()?.toByteArray() ?: return false
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(caller, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray() ?: return false
            }
            MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            return false
        }
        return sig.equals(STORE_SIGNER_SHA256, ignoreCase = true)
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, sa: Array<out String>?): Int = 0

    companion object {
        private const val FORMAT = "kalamboard-1"
        private const val JETPREF_DIR = "jetpref_datastore"
        private val USER_DBS = listOf("floris_user_dictionary", "floris_learning")
        private val EXT_DIRS = listOf("ime/theme", "ime/keyboard", "ime/languagepack")
        private const val STORE_PACKAGE = "org.amanlabs.store"
        private const val STORE_SIGNER_SHA256 =
            "0d69c5d80e24e587559e9c96035dab56135a4e70dad172cda1dfb3080ee690af"
    }
}
