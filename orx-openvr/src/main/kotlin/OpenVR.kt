package org.openrndr.extra.openvr

import mu.KotlinLogging
import org.lwjgl.openvr.*
import org.lwjgl.openvr.VR.*
import org.lwjgl.openvr.VRSystem.VRSystem_GetRecommendedRenderTargetSize
import org.lwjgl.openvr.VRSystem.VRSystem_GetStringTrackedDeviceProperty
import org.lwjgl.system.MemoryStack.stackPush
import org.openrndr.Extension
import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.events.Event
import org.openrndr.internal.gl3.ColorBufferGL3
import org.openrndr.internal.gl3.debugGLErrors
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector3


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
            var propagationCancelled: Boolean = false
    ) {
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
    val projection: Matrix44
        get() {
            return when (currentEye) {
                Eye.Left -> projectionLeft
                Eye.Right -> projectionRight
            }
        }
    val view: Matrix44
        get() {
            return when (currentEye) {
                Eye.Left -> viewLeft
                Eye.Right -> viewRight
            }
        }
}

class OpenVRExtension : Extension {
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
     * Initialize OpenVRExtension
     *
     * Call after OpenGL context has been created and made current
     */
    private fun vrInit() {
        try {
            logger.info("VR_IsRuntimeInstalled() = ${VR_IsRuntimeInstalled()}")
            logger.info("VR_RuntimePath() = ${VR_RuntimePath()}")
            logger.info("VR_IsHmdPresent() = ${VR_IsHmdPresent()}")

            stackPush().use { stack ->
                val peError = stack.mallocInt(1)
                val eType = EVRApplicationType_VRApplication_Scene
                val token = VR_InitInternal(peError, eType)
                if (peError[0] != 0) {
                    logger.error {
                        "Error symbol: ${VR_GetVRInitErrorAsSymbol(peError[0])}"
                        "Initialize Error: ${VR_GetVRInitErrorAsEnglishDescription(peError[0])}"
                        " https://github.com/ValveSoftware/openvr/wiki/HmdError"
                    }
                    enabled = false
                    return
                }

                OpenVR.create(token)
                logger.info(
                        "Model Number : " + VRSystem_GetStringTrackedDeviceProperty(
                                k_unTrackedDeviceIndex_Hmd,
                                ETrackedDeviceProperty_Prop_ModelNumber_String,
                                peError
                        )
                )
                logger.info(
                        "Serial Number: " + VRSystem_GetStringTrackedDeviceProperty(
                                k_unTrackedDeviceIndex_Hmd,
                                ETrackedDeviceProperty_Prop_SerialNumber_String,
                                peError
                        )
                )
                val pnWidth = stack.mallocInt(1)
                val pnHeight = stack.mallocInt(1)
                VRSystem_GetRecommendedRenderTargetSize(pnWidth, pnHeight)
                logger.debug("Suggested render target size: ${pnWidth[0]}x${pnHeight[0]}")
                rtLeft = renderTarget(pnWidth[0], pnHeight[0]) {
                    colorBuffer()
                }
                rtRight = renderTarget(pnWidth[0], pnHeight[0]) {
                    colorBuffer()
                }

                // get projection matrices
                val matrix44 = HmdMatrix44.create()
                VRSystem.VRSystem_GetProjectionMatrix(EVREye_Eye_Left, 0.1f, 500.0f, matrix44)
                val projectionLeft = convertHmdMatrix44(matrix44)
                VRSystem.VRSystem_GetProjectionMatrix(EVREye_Eye_Right, 0.1f, 500.0f, matrix44)
                val projectionRight = convertHmdMatrix44(matrix44)
//                logger.debug("projection Left:\n$projectionLeft")
//                logger.debug("projection Right:\n$projectionRight")
                // get eye offset matrices
                val matrix34 = HmdMatrix34.create()
                VRSystem.VRSystem_GetEyeToHeadTransform(EVREye_Eye_Left, matrix34)
                val eyeLeft = convertHmdMatrix34(matrix34)
                VRSystem.VRSystem_GetEyeToHeadTransform(EVREye_Eye_Right, matrix34)
                val eyeRight = convertHmdMatrix34(matrix34)
//                logger.debug("eye Left:\n$eyeLeft")
//                logger.debug("eye Right:\n$eyeRight")
                debugGLErrors { null }

                program.openvr.projectionLeft = projectionLeft
                program.openvr.projectionRight = projectionRight
                program.openvr.eyeLeft = eyeLeft
                program.openvr.eyeRight = eyeRight
            }
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
            "Initializing OpenVRExtension"
        }
        this.program = program
        vrInit()
    }

    fun teardown() {
        if (!enabled) return

        enabled = false
        VR_ShutdownInternal()
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
        val pRenderPoseArray = TrackedDevicePose.create(k_unMaxTrackedDeviceCount)
        val pGamePoseArray = TrackedDevicePose.create(k_unMaxTrackedDeviceCount)
        // TODO handle openvr events VRSystem_PollNextEvent and processVREvent
        VRCompositor.VRCompositor_WaitGetPoses(pRenderPoseArray, pGamePoseArray)
        // get first render pose use its mDeviceToAbsoluteTracking as head space matrix
        val view = convertHmdMatrix34(pRenderPoseArray[0].mDeviceToAbsoluteTracking())
        program.openvr.viewLeft = program.openvr.eyeLeft * view
        program.openvr.viewRight = program.openvr.eyeRight * view
    }

    override fun beforeDraw(drawer: Drawer, program: Program) {
        if (!enabled) return
        vrPreDraw()
    }

    private fun submitBuffers() {
        val texture = Texture.create()
        // poking into openrndr gl3 internals to get opengl texture ID
        texture.handle((rtLeft.colorBuffers[0] as ColorBufferGL3).texture.toLong())
        texture.eType(ETextureType_TextureType_OpenGL)
        texture.eColorSpace(EColorSpace_ColorSpace_Gamma)
        VRCompositor.VRCompositor_Submit(
                EVREye_Eye_Left,
                texture,
                null,
                EVRSubmitFlags_Submit_Default
        )
        texture.handle((rtRight.colorBuffers[0] as ColorBufferGL3).texture.toLong())
        VRCompositor.VRCompositor_Submit(
                EVREye_Eye_Right,
                texture,
                null,
                EVRSubmitFlags_Submit_Default
        )
//        // OpenVR compositor expects a texture object of target GL_TEXTURE_2D_MULTISAMPLE, ours is GL_TEXTURE_2D, leading to a "GL_INVALID_OPERATION error generated. Target doesn't match the texture's target."
//        // this is fixed in the openvr api since
//        GL11.glGetError() // This mismatch doesn't seem to be harmful, so clear error state :)
//        checkGLErrors { null }
    }

    override fun afterDraw(drawer: Drawer, program: Program) {
        if (!enabled) return

        submitBuffers()
    }

    private fun drawGrid(drawer: Drawer) {
        drawer.isolated {
            drawer.model = Matrix44.IDENTITY
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
    fun draw(drawer: Drawer, function: OpenVRExtension.() -> Unit) {
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

