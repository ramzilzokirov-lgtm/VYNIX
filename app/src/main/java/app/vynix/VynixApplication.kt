package app.vynix

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

class VynixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(VynixDownloader, Localization("en", "US"))
    }
}
