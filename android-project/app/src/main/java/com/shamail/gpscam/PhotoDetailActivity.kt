package com.shamail.gpscam

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shamail.gpscam.data.AppPreferences
import com.shamail.gpscam.data.AuthManager
import com.shamail.gpscam.data.GpsSnapshot
import com.shamail.gpscam.data.LocationSource
import com.shamail.gpscam.data.SavedLocationDatabase
import com.shamail.gpscam.data.TimestampMode
import com.shamail.gpscam.databinding.ActivityPhotoDetailBinding
import com.shamail.gpscam.location.LocationInputResolver
import com.shamail.gpscam.storage.EditablePhotoContext
import com.shamail.gpscam.storage.PhotoStorageManager
import com.shamail.gpscam.ui.SequentialDateTimePicker
import com.shamail.gpscam.watermark.WatermarkRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class PhotoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoDetailBinding
    private lateinit var prefs: AppPreferences

    private lateinit var currentPhotoUri: Uri
    private var currentPhotoName: String = "SHAMAIL_GPS.jpg"
    private var currentFilePath: String? = null
    private var currentProvenance: String = "Verified Capture Info"

    private var editableContext: EditablePhotoContext? = null
    private var previewBitmap: Bitmap? = null
    private var pendingEditedMillis: Long? = null
    private var pendingEditedGps: GpsSnapshot? = null
    private var isBusy = false

    private val mapPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!AuthManager.isAdmin(this)) return@registerForActivityResult
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data ?: return@registerForActivityResult
            val ctx = editableContext ?: return@registerForActivityResult
            val baseGps = pendingEditedGps ?: ctx.gps
            val title = data.getStringExtra("picked_title").orEmpty()
            val address = data.getStringExtra("picked_address").orEmpty()
            val lat = data.getDoubleExtra("picked_lat", baseGps.latitude)
            val lon = data.getDoubleExtra("picked_lon", baseGps.longitude)
            val wasBookmark = data.getBooleanExtra("picked_was_saved_bookmark", false)

            val updatedGps = baseGps.copy(
                hasFix = true,
                latitude = lat,
                longitude = lon,
                accuracyMeters = 0f,
                city = title,
                district = "",
                province = "",
                country = "",
                streetAddress = address,
                isOfflineAddress = false,
                locationSource = if (wasBookmark) LocationSource.SAVED_LOCATION else LocationSource.MANUAL_MAP
            )
            renderEditPreview(
                editedMillis = pendingEditedMillis ?: ctx.originalDisplayMillis,
                editedGps = updatedGps
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        val uriStr = intent.getStringExtra("photo_uri") ?: run {
            finish()
            return
        }
        currentPhotoUri = Uri.parse(uriStr)
        currentPhotoName = intent.getStringExtra("photo_name") ?: "SHAMAIL_GPS.jpg"
        currentFilePath = intent.getStringExtra("photo_path")
        currentProvenance = intent.getStringExtra("photo_provenance") ?: "Verified Capture Info"

        refreshHeaderAndPhotoView()
        applyAdminVisibility()

        binding.btnBackDetail.setOnClickListener { finish() }

        binding.btnSharePhoto.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, currentPhotoUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share GPS Watermarked Photo"))
        }

        binding.btnDeletePhoto.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Photo?")
                .setMessage("Permanently remove $currentPhotoName from ${PhotoStorageManager.RELATIVE_PICTURES_PATH}?")
                .setPositiveButton("Delete") { _, _ ->
                    val deleted = PhotoStorageManager.deletePhoto(this, currentPhotoUri, currentFilePath)
                    if (deleted) {
                        Toast.makeText(this, "Photo deleted", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this, "Unable to delete photo on this Android version", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnEditPhotoDateTime.setOnClickListener {
            if (!AuthManager.isAdmin(this)) return@setOnClickListener
            startDateTimeEditFlow()
        }

        binding.btnChangePhotoLocation.setOnClickListener {
            if (!AuthManager.isAdmin(this)) return@setOnClickListener
            startLocationChangeFlow()
        }

        binding.btnViewMetadataHistory.setOnClickListener {
            showMetadataAndHistoryDialog()
        }

        binding.tvDetailMeta.setOnClickListener {
            showMetadataAndHistoryDialog()
        }

        binding.btnCancelEditPreview.setOnClickListener {
            cancelEditPreview()
        }

        binding.btnSaveEditedCopy.setOnClickListener {
            if (!AuthManager.isAdmin(this)) return@setOnClickListener
            savePreviewedCopy()
        }
    }

    override fun onResume() {
        super.onResume()
        applyAdminVisibility()
        if (!AuthManager.isAdmin(this) && (pendingEditedMillis != null || pendingEditedGps != null)) {
            cancelEditPreview()
        }
    }

    private fun applyAdminVisibility() {
        val isAdmin = AuthManager.isAdmin(this)
        binding.layoutAdminEditActions.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.btnEditPhotoDateTime.isEnabled = isAdmin
        binding.btnChangePhotoLocation.isEnabled = isAdmin
    }

    private fun refreshHeaderAndPhotoView() {
        binding.tvDetailTitle.text = currentPhotoName
        binding.tvDetailMeta.text = PhotoStorageManager.RELATIVE_PICTURES_PATH
        binding.ivFullPhoto.setImageURI(currentPhotoUri)
    }

    private fun ensureEditableContextLoaded(onReady: (EditablePhotoContext) -> Unit) {
        if (!AuthManager.isAdmin(this) || isBusy) return
        val existingCtx = editableContext
        if (existingCtx != null) {
            onReady(existingCtx)
            return
        }

        val appCfg = prefs.loadConfig()
        isBusy = true
        binding.btnEditPhotoDateTime.isEnabled = false
        binding.btnChangePhotoLocation.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val loaded = PhotoStorageManager.loadEditablePhotoContext(
                context = this@PhotoDetailActivity,
                photoUri = currentPhotoUri,
                displayName = currentPhotoName,
                filePath = currentFilePath,
                fallbackConfig = appCfg
            )
            withContext(Dispatchers.Main) {
                isBusy = false
                val isAdminNow = AuthManager.isAdmin(this@PhotoDetailActivity)
                binding.btnEditPhotoDateTime.isEnabled = isAdminNow
                binding.btnChangePhotoLocation.isEnabled = isAdminNow
                if (loaded == null) {
                    Toast.makeText(
                        this@PhotoDetailActivity,
                        "Unable to load photo for watermark editing",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@withContext
                }
                editableContext = loaded
                onReady(loaded)
            }
        }
    }

    private fun startDateTimeEditFlow() {
        ensureEditableContextLoaded { ctx ->
            val appCfg = prefs.loadConfig()
            SequentialDateTimePicker.launch(
                activity = this,
                initialMillis = pendingEditedMillis ?: ctx.originalDisplayMillis,
                is24HourFormat = appCfg.is24HourFormat,
                dateFormatPattern = appCfg.dateFormatPattern
            ) { confirmedMillis ->
                renderEditPreview(
                    editedMillis = confirmedMillis,
                    editedGps = pendingEditedGps ?: ctx.gps
                )
            }
        }
    }

    private fun startLocationChangeFlow() {
        ensureEditableContextLoaded { ctx ->
            val currentGps = pendingEditedGps ?: ctx.gps
            val options = arrayOf(
                "Pick on Map / Search Place",
                "Choose Saved Location",
                "Enter Plus Code, Coordinates, or Location"
            )
            MaterialAlertDialogBuilder(this)
                .setTitle("Change Watermark Location")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> launchMapPickerForPhoto(currentGps)
                        1 -> showSavedLocationsChooser(ctx, currentGps)
                        2 -> showManualLocationDialog(ctx, currentGps)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun launchMapPickerForPhoto(baseGps: GpsSnapshot) {
        if (!AuthManager.isAdmin(this)) return
        val intent = Intent(this, LocationMapPickerActivity::class.java).apply {
            if (baseGps.hasFix) {
                putExtra("initial_lat", baseGps.latitude)
                putExtra("initial_lon", baseGps.longitude)
                putExtra("initial_title", baseGps.buildLocationHeadline())
                putExtra("initial_address", baseGps.streetAddress)
            }
        }
        mapPickerLauncher.launch(intent)
    }

    private fun showSavedLocationsChooser(ctx: EditablePhotoContext, baseGps: GpsSnapshot) {
        lifecycleScope.launch(Dispatchers.IO) {
            val savedItems = SavedLocationDatabase.getInstance(this@PhotoDetailActivity)
                .savedLocationDao()
                .getAll()
            withContext(Dispatchers.Main) {
                if (savedItems.isEmpty()) {
                    MaterialAlertDialogBuilder(this@PhotoDetailActivity)
                        .setTitle("No Saved Locations Yet")
                        .setMessage("Pick a location on the map first to save bookmarks or apply it directly.")
                        .setPositiveButton("Pick on Map") { _, _ ->
                            launchMapPickerForPhoto(baseGps)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@withContext
                }
                val labels = savedItems.map { "${it.label} — ${it.locationTitle}" }.toTypedArray()
                MaterialAlertDialogBuilder(this@PhotoDetailActivity)
                    .setTitle("Select Saved Location")
                    .setItems(labels) { _, idx ->
                        val chosen = savedItems[idx]
                        renderEditPreview(
                            editedMillis = pendingEditedMillis ?: ctx.originalDisplayMillis,
                            editedGps = chosen.toGpsSnapshot()
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun showManualLocationDialog(ctx: EditablePhotoContext, baseGps: GpsSnapshot) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val initialTitleText = if (baseGps.hasFix) baseGps.buildLocationHeadline() else ""
        var lookupAppliedQuery: String? = null

        val etQuickLookup = EditText(this).apply {
            hint = "Plus Code (e.g. P77M+VVC, Gujrat) or Coordinates"
            setSingleLine(true)
        }
        val btnLookup = com.google.android.material.button.MaterialButton(this).apply {
            text = "Get Location"
        }
        val etTitle = EditText(this).apply {
            hint = "Location Title (e.g. Sarai Alamgir, Punjab, Pakistan)"
            setText(initialTitleText)
            setSingleLine(true)
        }
        val etAddress = EditText(this).apply {
            hint = "Full Street / Area Address"
            setText(baseGps.streetAddress)
        }
        val etLat = EditText(this).apply {
            hint = "Latitude (e.g. 32.905644)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            if (baseGps.hasFix) {
                setText(String.format(Locale.US, "%.6f", baseGps.latitude))
            }
            setSingleLine(true)
        }
        val etLon = EditText(this).apply {
            hint = "Longitude (e.g. 73.749995)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            if (baseGps.hasFix) {
                setText(String.format(Locale.US, "%.6f", baseGps.longitude))
            }
            setSingleLine(true)
        }

        container.addView(etQuickLookup)
        container.addView(btnLookup)
        container.addView(etTitle)
        container.addView(etAddress)
        container.addView(etLat)
        container.addView(etLon)

        btnLookup.setOnClickListener {
            val quickText = etQuickLookup.text?.toString()?.trim().orEmpty()
            val latRaw = etLat.text?.toString()?.trim().orEmpty()
            val lonRaw = etLon.text?.toString()?.trim().orEmpty()
            val queryToResolve = quickText.ifBlank {
                if (latRaw.isNotEmpty() && lonRaw.isNotEmpty()) "$latRaw, $lonRaw" else ""
            }
            if (queryToResolve.isEmpty()) {
                Toast.makeText(
                    this,
                    "Enter a Plus Code or Latitude/Longitude first",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            btnLookup.isEnabled = false
            btnLookup.text = "Getting Location..."
            lifecycleScope.launch {
                val resolved = LocationInputResolver.resolveInput(
                    context = this@PhotoDetailActivity,
                    rawInput = queryToResolve,
                    referenceLat = if (baseGps.hasFix) baseGps.latitude else 31.520370,
                    referenceLon = if (baseGps.hasFix) baseGps.longitude else 74.358749
                )
                btnLookup.isEnabled = true
                btnLookup.text = "Get Location"
                if (resolved != null) {
                    lookupAppliedQuery = quickText
                    etTitle.setText(resolved.locationTitle)
                    etAddress.setText(resolved.fullAddress)
                    etLat.setText(String.format(Locale.US, "%.6f", resolved.latitude))
                    etLon.setText(String.format(Locale.US, "%.6f", resolved.longitude))
                    Toast.makeText(
                        this@PhotoDetailActivity,
                        "Location found: ${resolved.locationTitle}",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@PhotoDetailActivity,
                        "Could not find location. Try a full Plus Code or Lat, Lon.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Plus Code, Coordinates & Location")
            .setView(container)
            .setPositiveButton("Preview") { _, _ ->
                val quickText = etQuickLookup.text?.toString()?.trim().orEmpty()
                val titleText = etTitle.text?.toString()?.trim().orEmpty()
                val addressText = etAddress.text?.toString()?.trim().orEmpty()
                val latStr = etLat.text?.toString()?.trim().orEmpty()
                val lonStr = etLon.text?.toString()?.trim().orEmpty()

                val parsedLat = if (latStr.isEmpty()) baseGps.latitude else latStr.toDoubleOrNull()
                val parsedLon = if (lonStr.isEmpty()) baseGps.longitude else lonStr.toDoubleOrNull()

                if (quickText.isNotEmpty() && quickText != lookupAppliedQuery && titleText == initialTitleText) {
                    lifecycleScope.launch {
                        val resolved = LocationInputResolver.resolveInput(
                            context = this@PhotoDetailActivity,
                            rawInput = quickText,
                            referenceLat = if (baseGps.hasFix) baseGps.latitude else 31.520370,
                            referenceLon = if (baseGps.hasFix) baseGps.longitude else 74.358749
                        )
                        if (resolved != null) {
                            val resolvedGps = baseGps.copy(
                                hasFix = true,
                                latitude = resolved.latitude,
                                longitude = resolved.longitude,
                                accuracyMeters = 0f,
                                city = resolved.city.ifBlank { resolved.locationTitle },
                                district = resolved.district,
                                province = resolved.province,
                                country = resolved.country,
                                streetAddress = resolved.fullAddress,
                                isOfflineAddress = false,
                                locationSource = LocationSource.MANUAL_MAP
                            )
                            renderEditPreview(
                                editedMillis = pendingEditedMillis ?: ctx.originalDisplayMillis,
                                editedGps = resolvedGps
                            )
                        } else {
                            Toast.makeText(
                                this@PhotoDetailActivity,
                                "Could not resolve Plus Code or coordinates",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    return@setPositiveButton
                }

                if ((latStr.isNotEmpty() && (parsedLat == null || parsedLat !in -90.0..90.0)) ||
                    (lonStr.isNotEmpty() && (parsedLon == null || parsedLon !in -180.0..180.0))
                ) {
                    Toast.makeText(
                        this,
                        "Enter valid Latitude (-90..90) and Longitude (-180..180)",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                val updatedGps = baseGps.copy(
                    hasFix = true,
                    latitude = parsedLat ?: baseGps.latitude,
                    longitude = parsedLon ?: baseGps.longitude,
                    accuracyMeters = 0f,
                    city = titleText.ifBlank { baseGps.city },
                    district = if (titleText.isNotBlank()) "" else baseGps.district,
                    province = if (titleText.isNotBlank()) "" else baseGps.province,
                    country = if (titleText.isNotBlank()) "" else baseGps.country,
                    streetAddress = addressText,
                    isOfflineAddress = false,
                    locationSource = LocationSource.MANUAL_MAP
                )
                renderEditPreview(
                    editedMillis = pendingEditedMillis ?: ctx.originalDisplayMillis,
                    editedGps = updatedGps
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderEditPreview(editedMillis: Long, editedGps: GpsSnapshot) {
        if (!AuthManager.isAdmin(this)) return
        val ctx = editableContext ?: return
        val appCfg = prefs.loadConfig()
        val previewCfg = ctx.config.copy(
            timestampMode = TimestampMode.MANUAL,
            manualTimestampMillis = editedMillis,
            is24HourFormat = appCfg.is24HourFormat,
            dateFormatPattern = appCfg.dateFormatPattern,
            showWatermark = true
        )

        isBusy = true
        binding.btnSaveEditedCopy.isEnabled = false
        lifecycleScope.launch(Dispatchers.Default) {
            val logoBmp = WatermarkRenderer.loadLogoBitmap(this@PhotoDetailActivity, previewCfg)
            val rendered = WatermarkRenderer.renderOntoBitmap(
                source = ctx.sourceBitmap,
                config = previewCfg,
                gps = editedGps,
                logoBitmap = logoBmp,
                captureMillis = editedMillis,
                forceOpaqueBackground = false,
                replaceExistingWatermark = !ctx.hasCleanOriginal,
                oldGpsForRemoval = ctx.gps
            )
            val formattedStamp = previewCfg.formatTimestamp(editedGps, editedMillis)
            val locationLabel = if (editedGps.hasFix) editedGps.buildLocationHeadline() else "Location"
            withContext(Dispatchers.Main) {
                isBusy = false
                binding.btnSaveEditedCopy.isEnabled = true
                pendingEditedMillis = editedMillis
                pendingEditedGps = editedGps
                val oldPreview = previewBitmap
                previewBitmap = rendered
                binding.ivFullPhoto.setImageBitmap(rendered)
                if (oldPreview != null && oldPreview != rendered && oldPreview != ctx.sourceBitmap && !oldPreview.isRecycled) {
                    oldPreview.recycle()
                }
                binding.tvPreviewBanner.text = "Preview: $locationLabel · $formattedStamp"
                binding.tvDetailMeta.text = "Previewing custom watermark copy (original unchanged)"
                binding.layoutPreviewConfirmBar.visibility = View.VISIBLE
            }
        }
    }

    private fun cancelEditPreview() {
        pendingEditedMillis = null
        pendingEditedGps = null
        binding.layoutPreviewConfirmBar.visibility = View.GONE
        refreshHeaderAndPhotoView()
        val old = previewBitmap
        previewBitmap = null
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
    }

    private fun savePreviewedCopy() {
        if (!AuthManager.isAdmin(this) || isBusy) return
        val ctx = editableContext ?: return
        val editedMillis = pendingEditedMillis ?: ctx.originalDisplayMillis
        val editedGps = pendingEditedGps ?: ctx.gps
        val appCfg = prefs.loadConfig()

        val hasTimeChange = abs(editedMillis - ctx.originalDisplayMillis) > 1000L ||
            abs(editedMillis - ctx.originalCaptureMillis) > 60_000L
        val hasLocChange = editedGps.locationSource != LocationSource.LIVE_GPS ||
            editedGps.buildLocationHeadline() != ctx.gps.buildLocationHeadline() ||
            editedGps.streetAddress != ctx.gps.streetAddress ||
            abs(editedGps.latitude - ctx.gps.latitude) > 0.0001 ||
            abs(editedGps.longitude - ctx.gps.longitude) > 0.0001

        isBusy = true
        binding.btnSaveEditedCopy.isEnabled = false
        binding.btnCancelEditPreview.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val savedPair = PhotoStorageManager.saveEditedDateTimeCopy(
                context = this@PhotoDetailActivity,
                editable = ctx,
                editedMillis = editedMillis,
                is24HourFormat = appCfg.is24HourFormat,
                dateFormatPattern = appCfg.dateFormatPattern,
                editedGps = editedGps
            )
            withContext(Dispatchers.Main) {
                isBusy = false
                binding.btnSaveEditedCopy.isEnabled = true
                binding.btnCancelEditPreview.isEnabled = true

                if (savedPair != null) {
                    val (newUri, newName) = savedPair
                    currentPhotoUri = newUri
                    currentPhotoName = newName
                    currentFilePath = null
                    currentProvenance = when {
                        hasTimeChange && hasLocChange -> "Custom Location & Time"
                        hasLocChange -> "Custom Location Info"
                        else -> "Custom Date & Time"
                    }
                    pendingEditedMillis = null
                    pendingEditedGps = null
                    binding.layoutPreviewConfirmBar.visibility = View.GONE
                    refreshHeaderAndPhotoView()

                    val oldPreview = previewBitmap
                    previewBitmap = null
                    if (oldPreview != null && !oldPreview.isRecycled) {
                        oldPreview.recycle()
                    }
                    editableContext?.let { oldCtx ->
                        if (!oldCtx.sourceBitmap.isRecycled) oldCtx.sourceBitmap.recycle()
                    }
                    editableContext = null

                    Toast.makeText(
                        this@PhotoDetailActivity,
                        "Saved separate copy: $newName",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@PhotoDetailActivity,
                        "Failed to save new photo copy",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showMetadataAndHistoryDialog() {
        val uriSnapshot = currentPhotoUri
        val nameSnapshot = currentPhotoName
        val pathSnapshot = currentFilePath

        lifecycleScope.launch(Dispatchers.IO) {
            val record = PhotoStorageManager.loadPhotoMetadataRecord(
                context = this@PhotoDetailActivity,
                photoUri = uriSnapshot,
                displayName = nameSnapshot,
                filePath = pathSnapshot
            )
            withContext(Dispatchers.Main) {
                val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.US)
                val sb = StringBuilder()

                sb.append("ORIGINAL CAPTURE INFO (EXIF PRESERVED)\n")
                sb.append("• Capture Date & Time: ")
                sb.append(record.originalCaptureMillis?.let { fmt.format(Date(it)) } ?: "Not embedded in source")
                sb.append("\n• Original GPS Coordinates: ")
                if (record.originalLatitude != null && record.originalLongitude != null) {
                    sb.append(String.format(Locale.US, "%.6f°, %.6f°", record.originalLatitude, record.originalLongitude))
                } else {
                    sb.append("Not recorded")
                }
                if (!record.originalLocationTitle.isNullOrBlank()) {
                    sb.append("\n• Original Place: ${record.originalLocationTitle}")
                }
                if (!record.cameraInfo.isNullOrBlank()) {
                    sb.append("\n• Camera Device: ${record.cameraInfo}")
                }
                if (!record.sourcePhotoName.isNullOrBlank()) {
                    sb.append("\n• Source Photo: ${record.sourcePhotoName}")
                }

                sb.append("\n\nWATERMARK DISPLAY INFO\n")
                sb.append("• Displayed Time (${if (record.isCustomTimestamp) "Custom Info" else "Original Capture"}): ")
                sb.append(fmt.format(Date(record.watermarkDateTimeMillis)))
                if (record.watermarkLocationTitle.isNotBlank() || record.watermarkLatitude != null) {
                    sb.append("\n• Displayed Location (${if (record.isCustomLocation) "Custom Info" else "Original GPS"}): ")
                    sb.append(record.watermarkLocationTitle.ifBlank { "Coordinates attached" })
                }
                if (record.watermarkAddress.isNotBlank()) {
                    sb.append("\n• Displayed Address: ${record.watermarkAddress}")
                }
                if (record.watermarkLatitude != null && record.watermarkLongitude != null) {
                    sb.append(
                        String.format(
                            Locale.US,
                            "\n• Displayed Coords: %.6f°, %.6f°",
                            record.watermarkLatitude,
                            record.watermarkLongitude
                        )
                    )
                }

                sb.append("\n\nEDIT HISTORY\n")
                if (record.history.isEmpty()) {
                    sb.append("• Original photo · No custom watermark edits recorded.")
                } else {
                    record.history.forEachIndexed { idx, entry ->
                        sb.append("${idx + 1}. ${entry.actionLabel}\n")
                        sb.append("   Recorded: ${fmt.format(Date(entry.recordedAtMillis))}\n")
                        if (!entry.sourcePhotoName.isNullOrBlank()) {
                            sb.append("   Source: ${entry.sourcePhotoName}\n")
                        }
                        if (entry.customDateTimeMillis != null) {
                            sb.append("   Custom Time: ${fmt.format(Date(entry.customDateTimeMillis))}\n")
                        }
                        if (!entry.customLocationTitle.isNullOrBlank()) {
                            sb.append("   Custom Place: ${entry.customLocationTitle}\n")
                        }
                    }
                }

                AlertDialog.Builder(this@PhotoDetailActivity)
                    .setTitle("Photo Info & Edit History")
                    .setMessage(sb.toString().trim())
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.ivFullPhoto.setImageDrawable(null)
        previewBitmap?.let { if (!it.isRecycled) it.recycle() }
        editableContext?.let { if (!it.sourceBitmap.isRecycled) it.sourceBitmap.recycle() }
    }
}
