package com.amr3d.preview

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var glViewerView: GLViewerView
    private lateinit var emptyStateText: TextView
    private lateinit var btnOpenFile: Button
    private lateinit var btnMeasureTool: ToggleButton
    private lateinit var btnInspect: Button
    private lateinit var btnWhatsapp: android.widget.ImageButton
    private lateinit var btnResetView: Button
    private lateinit var btnWireframe: ToggleButton
    private lateinit var btnColor: Button
    private lateinit var btnUnit: Button
    private lateinit var btnExport: Button
    private lateinit var viewCube: ViewCubeView
    private lateinit var measurementCard: CardView
    private lateinit var measurementText: TextView
    private lateinit var inspectionCard: CardView
    private lateinit var inspectionText: TextView

    private var currentModel: STLModel? = null
    private var measureModeOn = false
    private var currentUnit = MeasurementUnit.MM

    private val openDocumentLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            loadSTLFile(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        wireUpListeners()

        // If the app was launched from "Open with..." on an .stl file
        intent?.data?.let { uri ->
            loadSTLFile(uri)
        }
    }

    private fun bindViews() {
        glViewerView = findViewById(R.id.glViewerView)
        emptyStateText = findViewById(R.id.emptyStateText)
        btnOpenFile = findViewById(R.id.btnOpenFile)
        btnMeasureTool = findViewById(R.id.btnMeasureTool)
        btnInspect = findViewById(R.id.btnInspect)
        btnResetView = findViewById(R.id.btnResetView)
        btnWhatsapp = findViewById(R.id.btnWhatsapp)
        btnWireframe = findViewById(R.id.btnWireframe)
        btnColor = findViewById(R.id.btnColor)
        btnUnit = findViewById(R.id.btnUnit)
        btnExport = findViewById(R.id.btnExport)
        viewCube = findViewById(R.id.viewCube)
        measurementCard = findViewById(R.id.measurementCard)
        measurementText = findViewById(R.id.measurementText)
        inspectionCard = findViewById(R.id.inspectionCard)
        inspectionText = findViewById(R.id.inspectionText)
    }

    private fun wireUpListeners() {
        btnOpenFile.setOnClickListener {
            // STL files don't always have a registered MIME type on Android, so we
            // accept "*/*" and validate the extension/content ourselves in the parser.
            openDocumentLauncher.launch(arrayOf("*/*"))
        }

        btnMeasureTool.setOnCheckedChangeListener { _, isChecked ->
            measureModeOn = isChecked
            if (!isChecked) {
                glViewerView.stlRenderer.clearMeasurementPoints()
                measurementCard.visibility = View.GONE
            } else {
                inspectionCard.visibility = View.GONE
                Toast.makeText(
                    this,
                    "اضغط على نقطتين على سطح الموديل لقياس المسافة بينهما",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        btnInspect.setOnClickListener {
            val model = currentModel
            if (model == null) {
                Toast.makeText(this, "افتح ملف STL أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showInspectionReport(model)
        }

        btnResetView.setOnClickListener {
            resetCamera()
        }

        btnWhatsapp.setOnClickListener {
            openWhatsapp()
        }

        btnWireframe.setOnCheckedChangeListener { _, isChecked ->
            glViewerView.stlRenderer.wireframeMode = isChecked
        }

        btnColor.setOnClickListener {
            showColorPicker()
        }

        btnUnit.setOnClickListener {
            cycleUnit()
        }

        btnExport.setOnClickListener {
            exportCurrentView()
        }

        viewCube.onFaceSelected = { face ->
            val renderer = glViewerView.stlRenderer
            renderer.rotationX = face.rotX
            renderer.rotationY = face.rotY
        }

        glViewerView.onSingleTap = { x, y ->
            if (measureModeOn) {
                handleMeasurementTap(x, y)
            }
        }

        inspectionCard.setOnClickListener {
            inspectionCard.visibility = View.GONE
        }

        measurementCard.setOnClickListener {
            measurementCard.visibility = View.GONE
            glViewerView.stlRenderer.clearMeasurementPoints()
        }
    }

    private fun resetCamera() {
        val renderer = glViewerView.stlRenderer
        renderer.rotationX = -25f
        renderer.rotationY = 35f
        renderer.scaleFactor = 1f
        renderer.panX = 0f
        renderer.panY = 0f
    }

    private fun showColorPicker() {
        val items = arrayOf(
            "── لون الموديل ──",
            "أزرق (افتراضي)", "نحاسي", "رمادي", "أخضر", "أحمر", "ذهبي",
            "── لون الخلفية ──",
            "داكن (افتراضي)", "أسود", "رمادي داكن", "أبيض", "كحلي"
        )
        AlertDialog.Builder(this)
            .setTitle("اختر اللون")
            .setItems(items) { _, which ->
                when (which) {
                    1 -> glViewerView.stlRenderer.setModelColor(0.45f, 0.75f, 0.95f)
                    2 -> glViewerView.stlRenderer.setModelColor(0.80f, 0.50f, 0.25f)
                    3 -> glViewerView.stlRenderer.setModelColor(0.65f, 0.65f, 0.68f)
                    4 -> glViewerView.stlRenderer.setModelColor(0.40f, 0.75f, 0.45f)
                    5 -> glViewerView.stlRenderer.setModelColor(0.85f, 0.35f, 0.30f)
                    6 -> glViewerView.stlRenderer.setModelColor(0.90f, 0.75f, 0.30f)
                    8 -> glViewerView.stlRenderer.setBackgroundColor(0.10f, 0.11f, 0.13f)
                    9 -> glViewerView.stlRenderer.setBackgroundColor(0.02f, 0.02f, 0.02f)
                    10 -> glViewerView.stlRenderer.setBackgroundColor(0.22f, 0.24f, 0.27f)
                    11 -> glViewerView.stlRenderer.setBackgroundColor(0.92f, 0.92f, 0.92f)
                    12 -> glViewerView.stlRenderer.setBackgroundColor(0.05f, 0.08f, 0.18f)
                }
            }
            .show()
    }

    private fun cycleUnit() {
        currentUnit = when (currentUnit) {
            MeasurementUnit.MM -> MeasurementUnit.CM
            MeasurementUnit.CM -> MeasurementUnit.INCH
            MeasurementUnit.INCH -> MeasurementUnit.MM
        }
        btnUnit.text = currentUnit.label

        // Refresh any currently visible reports so the displayed numbers match the
        // newly selected unit immediately.
        currentModel?.let { model ->
            if (inspectionCard.visibility == View.VISIBLE) {
                showInspectionReport(model)
            }
        }
        val points = glViewerView.stlRenderer.getMeasurementPoints()
        if (points.size == 2) {
            updateMeasurementText(points[0], points[1])
        }
    }

    private fun exportCurrentView() {
        if (currentModel == null) {
            Toast.makeText(this, "افتح ملف STL أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        val renderer = glViewerView.stlRenderer
        val width = renderer.getSurfaceWidth()
        val height = renderer.getSurfaceHeight()
        if (width <= 0 || height <= 0) return

        // Bitmap capture must happen on the GL thread since it reads the active framebuffer.
        glViewerView.queueEvent {
            val bitmap = renderer.captureFrame(width, height)
            runOnUiThread {
                saveAndShareBitmap(bitmap)
            }
        }
    }

    private fun saveAndShareBitmap(bitmap: android.graphics.Bitmap) {
        try {
            val picturesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val file = File(picturesDir, "Amr3D_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }

            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )

            Toast.makeText(this, R.string.export_success, Toast.LENGTH_SHORT).show()

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.export_image)))
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر حفظ الصورة: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openWhatsapp() {
        val phone = getString(R.string.whatsapp_number)
        val message = Uri.encode(getString(R.string.whatsapp_message))

        // Try the native WhatsApp app intent first
        try {
            val nativeUri = Uri.parse("whatsapp://send?phone=$phone&text=$message")
            val nativeIntent = Intent(Intent.ACTION_VIEW, nativeUri)
            nativeIntent.setPackage("com.whatsapp")
            startActivity(nativeIntent)
            return
        } catch (e: Exception) {
            // WhatsApp not installed or failed, fall back below
        }

        // Fallback: open via wa.me link in browser (works even without WhatsApp installed
        // on the device, e.g. WhatsApp Web on a tablet)
        try {
            val webUri = Uri.parse("https://wa.me/$phone?text=$message")
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح واتساب", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSTLFile(uri: Uri) {
        Toast.makeText(this, "جارٍ تحميل الملف...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val model = withContext(Dispatchers.Default) {
                    STLParser.parse(this@MainActivity, uri)
                }
                currentModel = model
                glViewerView.stlRenderer.setModel(model)
                emptyStateText.visibility = View.GONE
                inspectionCard.visibility = View.GONE
                measurementCard.visibility = View.GONE
                btnMeasureTool.isChecked = false
                btnWireframe.isChecked = false

                Toast.makeText(
                    this@MainActivity,
                    "تم تحميل الموديل: ${model.triangleCount} مثلث",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: STLParseException) {
                Toast.makeText(this@MainActivity, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "تعذر قراءة الملف. تأكد أنه ملف STL صالح.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun handleMeasurementTap(screenX: Float, screenY: Float) {
        val model = currentModel ?: return
        val renderer = glViewerView.stlRenderer

        val ray = RayPicker.screenPointToRay(
            screenX, screenY,
            renderer.getSurfaceWidth(), renderer.getSurfaceHeight(),
            renderer.getCurrentModelMatrix(),
            renderer.getCurrentViewMatrix(),
            renderer.getCurrentProjectionMatrix()
        )

        val hitPoint = RayPicker.findClosestIntersection(ray, model)
        if (hitPoint == null) {
            Toast.makeText(this, "لم يتم تحديد نقطة على سطح الموديل", Toast.LENGTH_SHORT).show()
            return
        }

        renderer.addMeasurementPoint(hitPoint)
        val points = renderer.getMeasurementPoints()

        if (points.size == 2) {
            updateMeasurementText(points[0], points[1])
        } else {
            measurementText.text = "نقطة أولى محددة — اضغط على نقطة ثانية"
            measurementCard.visibility = View.VISIBLE
        }
    }

    private fun updateMeasurementText(p1: FloatArray, p2: FloatArray) {
        val distance = MeasurementTools.distanceBetween(p1, p2, currentUnit)
        measurementText.text = String.format(
            Locale.US,
            "المسافة: %.3f %s\n(X:%.2f Y:%.2f Z:%.2f) → (X:%.2f Y:%.2f Z:%.2f)",
            distance, currentUnit.label,
            p1[0], p1[1], p1[2],
            p2[0], p2[1], p2[2]
        )
        measurementCard.visibility = View.VISIBLE
    }

    private fun showInspectionReport(model: STLModel) {
        // Toggle: إذا كانت ظاهرة، أخفيها
        if (inspectionCard.visibility == View.VISIBLE) {
            inspectionCard.visibility = View.GONE
            return
        }

        val report = MeasurementTools.inspect(model, currentUnit)
        val u = report.unit.label

        // عرض الأبعاد فقط بصيغة واضحة بدون تفاصيل تقنية
        val sb = StringBuilder()
        sb.append("📐 أبعاد الموديل\n")
        sb.append("─────────────────\n")
        sb.append(String.format(java.util.Locale.US, "الطول (X):    %.2f %s\n", report.width, u))
        sb.append(String.format(java.util.Locale.US, "العرض (Y):   %.2f %s\n", report.depth, u))
        sb.append(String.format(java.util.Locale.US, "الارتفاع (Z): %.2f %s", report.height, u))

        inspectionText.text = sb.toString()
        inspectionCard.visibility = View.VISIBLE
        measurementCard.visibility = View.GONE
    }
}
