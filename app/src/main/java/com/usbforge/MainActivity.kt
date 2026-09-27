package com.usbforge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.usbforge.ui.MainScreen
import com.usbforge.ui.theme.UsbForgeTheme
import com.usbforge.vm.MainViewModel

/**
 * Tek Activity. Tüm ekranlar Compose ile tek bir `MainScreen` içinde
 * birleştirilmiştir; yönlendirme kütüphanesi gereksizdir.
 *
 * ## USB cihaz takılma olayı
 * `USB_DEVICE_ATTACHED` intent'i `launchMode="singleTask"` sayesinde mevcut
 * Activity'yi yeniden başlatır (`onNewIntent`) ve cihaz otomatik seçilir.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Dosya seçici: ISO/IMG dosyaları. */
    private val pickIsoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.selectIso(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            UsbForgeTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val job by viewModel.jobState.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) {
                    viewModel.scanDevices()
                }

                MainScreen(
                    state = state,
                    job = job,
                    onSelectDevice = viewModel::selectDevice,
                    onRescan = viewModel::scanDevices,
                    onOperation = viewModel::setOperation,
                    onFileSystem = viewModel::setFileSystem,
                    onScheme = viewModel::setScheme,
                    onLabel = viewModel::setLabel,
                    onPickIso = { pickIsoLauncher.launch(ISO_MIME_TYPES) },
                    onVerify = viewModel::setVerify,
                    onStart = viewModel::start,
                    onCancel = viewModel::cancel,
                    onClearJob = viewModel::resetJob,
                    onDismissMessage = viewModel::dismissMessage,
                )
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Cihaz takılıp çıkarıldığında liste tazelenir.
        viewModel.scanDevices()
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let { viewModel.onUsbAttached(it) }
    }

    private companion object {
        /**
         * ISO/IMG dosya türleri. Resmî MIME tipi `application/x-iso9660-image`
         * standart değildir; bu yüzden tüm ikili türler kabul edilir ve
         * dosya adı uzantısına göre ayrım kullanıcıya bırakılır.
         */
        val ISO_MIME_TYPES = arrayOf(
            "application/octet-stream",
            "application/x-iso9660-image",
            "application/x-cd-image",
            "application/x-raw-disk-image",
            "application/gzip",
            "application/x-gzip",
            "application/x-xz",
            "application/x-bzip2",
            "application/x-lzma",
        )
    }
}
