package moe.reimu.nekoassistant.wrappers

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.IContentProvider
import android.os.Binder


@SuppressLint("SoonBlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi")
class FakeContext: ContextWrapper(Workarounds.getInstance().getSystemContext()) {
    private val contentResolver: ContentResolver = object : ContentResolver(this) {
        @Suppress("unused") // @Override (but super-class method not visible)
        protected fun acquireProvider(c: Context, name: String): IContentProvider? {
            return ActivityManagerWrapper.get().getContentProviderExternal(name, Binder())
        }

        @Suppress("unused") // @Override (but super-class method not visible)
        fun releaseProvider(icp: IContentProvider?): Boolean {
            return false
        }

        @Suppress("unused") // @Override (but super-class method not visible)
        protected fun acquireUnstableProvider(c: Context?, name: String?): IContentProvider? {
            return null
        }

        @Suppress("unused") // @Override (but super-class method not visible)
        fun releaseUnstableProvider(icp: IContentProvider?): Boolean {
            return false
        }

        @Suppress("unused") // @Override (but super-class method not visible)
        fun unstableProviderDied(icp: IContentProvider?) {
            // ignore
        }
    }

    override fun getApplicationContext() = this
    override fun createPackageContext(packageName: String?, flags: Int) = this
    override fun getPackageName() = PACKAGE_NAME
    override fun getOpPackageName() = PACKAGE_NAME
    override fun getAttributionSource(): AttributionSource {
        val builder: AttributionSource.Builder = AttributionSource.Builder(2000) // android.uid.shell
        builder.setPackageName(PACKAGE_NAME)
        return builder.build()
    }
    override fun getContentResolver() = contentResolver

    companion object {
        const val PACKAGE_NAME: String = "com.android.shell"
        const val ROOT_UID: Int = 0

        private val INSTANCE_LOCK = Any()
        private var INSTANCE: FakeContext? = null

        fun getInstance(): FakeContext {
            synchronized(INSTANCE_LOCK) {
                if (INSTANCE == null) {
                    INSTANCE = FakeContext()
                }
            }
            return INSTANCE!!
        }
    }
}