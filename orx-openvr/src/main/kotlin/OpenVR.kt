import mu.KotlinLogging
import org.lwjgl.opengl.GL11
import org.lwjgl.openvr.*
import org.openrndr.Extension
import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.events.Event
import org.openrndr.internal.gl3.ColorBufferGL3
import org.openrndr.internal.gl3.checkGLErrors
import org.openrndr.internal.gl3.debugGLErrors
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector3
import java.nio.IntBuffer

fun convertHmdMatrix44(m: HmdMatrix44): Matrix44 {
    return Matrix44(
            m.m(0).toDouble(), m.m(1).toDouble(), m.m(2).toDouble(), m.m(3).toDouble(),
            m.m(4).toDouble(), m.m(5).toDouble(), m.m(6).toDouble(), m.m(7).toDouble(),
            m.m(8).toDouble(), m.m(8).toDouble(), m.m(10).toDouble(), m.m(11).toDouble(),
            m.m(12).toDouble(), m.m(13).toDouble(), m.m(14).toDouble(), m.m(15).toDouble()
    )
}

fun convertHmdMatrix34(m: HmdMatrix34): Matrix44 {
    return Matrix44(
            m.m(0).toDouble(), m.m(4).toDouble(), m.m(8).toDouble(), 0.0,
            m.m(1).toDouble(), m.m(5).toDouble(), m.m(9).toDouble(), 0.0,
            m.m(2).toDouble(), m.m(6).toDouble(), m.m(10).toDouble(), 0.0,
            m.m(3).toDouble(), m.m(7).toDouble(), m.m(11).toDouble(), 1.0
    )
}

enum class Eye {
    Left,
    Right,
}

// the name events is not entirely accurate as it contains both events and state
class OpenVRHMDEvents {
    class OpenVRHMDEvent(
            var propagationCancelled: Boolean = false) {
        fun cancelPropagation() {
            propagationCancelled = true
        }
    }

    val updated = Event<OpenVRHMDEvent>().postpone(true)

    var currentEye = Eye.Left
    var projectionLeft = Matrix44.IDENTITY
    var projectionRight = Matrix44.IDENTITY

    // relative to head
    var eyeLeft = Matrix44.IDENTITY
    var eyeRight = Matrix44.IDENTITY
    var viewLeft = Matrix44.IDENTITY
    var viewRight = Matrix44.IDENTITY

    // generic camera API
    var projection: Matrix44 = Matrix44.IDENTITY
        get() {
            return when (currentEye) {
                Eye.Left -> projectionLeft
                Eye.Right -> projectionRight
            }
        }
    var view: Matrix44 = Matrix44.IDENTITY
        get() {
            return when (currentEye) {
                Eye.Left -> viewLeft
                Eye.Right -> viewRight
            }
        }
}

class OpenVR : Extension {
    private val logger = KotlinLogging.logger {}
    override var enabled = true
    var showGrid = true

    private lateinit var rtLeft: RenderTarget
    private lateinit var rtRight: RenderTarget

    private lateinit var program: Program

    private val grid = vertexBuffer(
            vertexFormat {
                position(3)
            }
            , 4 * 21).apply {
        put {
            for (x in -10..10) {
                write(Vector3(x.toDouble(), 0.0, -10.0))
                write(Vector3(x.toDouble(), 0.0, 10.0))
                write(Vector3(-10.0, 0.0, x.toDouble()))
                write(Vector3(10.0, 0.0, x.toDouble()))
            }
        }
    }

    /**
     * Initialize OprnVR
     *
     * Call after OpenGL context has been created and made current
     */
    private fun vrInit() {
        try {
            val peError: IntBuffer = IntBuffer.allocate(1)
            val eType = VR.EVRApplicationType_VRApplication_Scene
            val token = VR.VR_InitInternal(peError, eType)
            if (peError.get(0) != 0) {
                logger.error {
                    "Initialize Error: ${VR.VR_GetVRInitErrorAsEnglishDescription(peError.get(0))}"
                    " https://github.com/ValveSoftware/openvr/wiki/HmdError"
                }
                enabled = false
                return
            }
            OpenVR.create(token)
            logger.debug("using runtime: ${VR.VR_RuntimePath()} ")
            val pnWidth: IntBuffer = IntBuffer.allocate(1)
            val pnHeight: IntBuffer = IntBuffer.allocate(1)
//            EXCEPTION_ACCESS_VIOLATION?
//            VRSystem.VRSystem_GetRecommendedRenderTargetSize(pnWidth, pnHeight)
            // hack hack, hardcode some size instead
            pnWidth.put(2048)
            pnWidth.rewind()
            pnHeight.put(2048)
            pnHeight.rewind()
            logger.debug("Suggested render target size: ${pnWidth.get(0)}x${pnHeight.get(0)}")
            rtLeft = renderTarget(pnWidth.get(0), pnHeight.get(0)) {
                colorBuffer()
            }
            rtRight = renderTarget(pnWidth.get(0), pnHeight.get(0)) {
                colorBuffer()
            }
            // get projection matrices
            val matrix44 = HmdMatrix44.create()
            VRSystem.VRSystem_GetProjectionMatrix(VR.EVREye_Eye_Left, 0.1f, 500.0f, matrix44)
            val projectionLeft = convertHmdMatrix44(matrix44)
            VRSystem.VRSystem_GetProjectionMatrix(VR.EVREye_Eye_Right, 0.1f, 500.0f, matrix44)
            val projectionRight = convertHmdMatrix44(matrix44)
            logger.debug("projection Left:\n$projectionLeft")
            logger.debug("projection Right:\n$projectionRight")
            // get eye offset matrices
            val matrix34 = HmdMatrix34.create()
            VRSystem.VRSystem_GetEyeToHeadTransform(VR.EVREye_Eye_Left, matrix34)
            val eyeLeft = convertHmdMatrix34(matrix34)
            VRSystem.VRSystem_GetEyeToHeadTransform(VR.EVREye_Eye_Right, matrix34)
            val eyeRight = convertHmdMatrix34(matrix34)
            logger.debug("eye Left:\n$eyeLeft")
            logger.debug("eye Right:\n$eyeRight")
            debugGLErrors { null }

            program.openvr.projectionLeft = projectionLeft
            program.openvr.projectionRight = projectionRight
            program.openvr.eyeLeft = eyeLeft
            program.openvr.eyeRight = eyeRight

        } catch (e: Exception) {
            logger.error("Disabling because no OpenVR support due to: $e")
            enabled = false
        }
    }

    private fun deliverEvents() {
        program.openvr.updated.deliver()
    }

    override fun setup(program: Program) {
        logger.debug {
            "Initializing OpenVR"
        }
        this.program = program
        vrInit()
    }

    fun teardown() {
        if (!enabled) return

        enabled = false
        VR.VR_ShutdownInternal()
        logger.debug("Cleaned up VR")
    }

    /**
     * Does all VR things at the start of a frame
     *
     * It's best to call this as late as possible before any rendering will be done.
     * This because we get the HMD orientation here and we want to reduce the lag as
     * much as possible.
     */
    private fun vrPreDraw() {
        val pRenderPoseArray = TrackedDevicePose.create(VR.k_unMaxTrackedDeviceCount)
        val pGamePoseArray = TrackedDevicePose.create(VR.k_unMaxTrackedDeviceCount)
        // TODO handle openvr events VRSystem_PollNextEvent and processVREvent
        VRCompositor.VRCompositor_WaitGetPoses(pRenderPoseArray, pGamePoseArray)
        // get first render pose use its mDeviceToAbsoluteTracking as head space matrix
        val view = convertHmdMatrix34(pRenderPoseArray.get(0).mDeviceToAbsoluteTracking())
        program.openvr.viewLeft = program.openvr.eyeLeft * view
        program.openvr.viewRight = program.openvr.eyeRight * view
    }

    override fun beforeDraw(drawer: Drawer, program: Program) {
        if (!enabled) return
        vrPreDraw()
    }

    private fun submitBuffers() {
        val texture = Texture.create()
        texture.handle((rtLeft.colorBuffers[0] as ColorBufferGL3).texture.toLong())
        texture.eType(VR.ETextureType_TextureType_OpenGL)
        texture.eColorSpace(VR.EColorSpace_ColorSpace_Gamma)
        VRCompositor.VRCompositor_Submit(
                VR.EVREye_Eye_Left,
                texture,
                null,
                VR.EVRSubmitFlags_Submit_Default
        )
        texture.handle((rtRight.colorBuffers[0] as ColorBufferGL3).texture.toLong())
        VRCompositor.VRCompositor_Submit(
                VR.EVREye_Eye_Right,
                texture,
                null,
                VR.EVRSubmitFlags_Submit_Default
        )
        // TODO(VR): OpenVR compositor expects a texture object of target GL_TEXTURE_2D_MULTISAMPLE, ours is GL_TEXTURE_2D, leading to a "GL_INVALID_OPERATION error generated. Target doesn't match the texture's target."
        GL11.glGetError() // This mismatch doesn't seem to be harmful, so clear error state :)
        checkGLErrors { null }
    }

    override fun afterDraw(drawer: Drawer, program: Program) {
        if (!enabled) return

        submitBuffers()
    }

    private fun drawGrid(drawer: Drawer) {
        drawer.isolated {
            drawer.model = Matrix44.IDENTITY;
            drawer.translate(0.0, -2.0, 0.0)

            drawer.fill = ColorRGBa.BLACK
            drawer.stroke = ColorRGBa.BLACK
            drawer.vertexBuffer(grid, DrawPrimitive.LINES)

            // Axis cross
            drawer.fill = ColorRGBa.RED
            drawer.lineSegment(Vector3.ZERO, Vector3.UNIT_X)

            drawer.fill = ColorRGBa.GREEN
            drawer.lineSegment(Vector3.ZERO, Vector3.UNIT_Y)

            drawer.fill = ColorRGBa.BLUE
            drawer.lineSegment(Vector3.ZERO, Vector3.UNIT_Z)
        }
    }

    /**
     * Draws on the OpenVR headset.
     *
     * The passed draw function is called twice with the drawer's projection and view set
     * for left and right eyes.
     */
    fun draw(drawer: Drawer, function: OpenVR.() -> Unit) {
        if (!enabled) {
            function()
            return
        }

        // TODO(VR): if rendering separately, can we use just one target? saving some gpu mem
        program.openvr.currentEye = Eye.Left
        drawer.projection = program.openvr.projection
        drawer.view = program.openvr.view
        rtLeft.bind()
        function()
        if (showGrid) {
            drawGrid(drawer)
        }
        rtLeft.unbind()

        program.openvr.currentEye = Eye.Right
        drawer.projection = program.openvr.projection
        drawer.view = program.openvr.view
        rtRight.bind()
        function()
        if (showGrid) {
            drawGrid(drawer)
        }
        rtRight.unbind()
    }
}

val programOpenVREvents = mutableMapOf<Program, OpenVRHMDEvents>()
val Program.openvr: OpenVRHMDEvents get() = programOpenVREvents.getOrPut(this) { OpenVRHMDEvents() }

