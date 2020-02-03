# orx-openvr

A stab at OpenVR. Very basic, no controller events yet.

# Usage

```kotlin
import org.openrndr.*
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.isolated
import org.openrndr.extra.openvr.OpenVRExtension
import org.openrndr.extra.openvr.openvr
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3

class VRExample: Program() {
    private lateinit var openvrExt: OpenVRExtension

    override fun setup() {
        super.setup()

        openvrExt = OpenVRExtension()
        extend(openvrExt)

        keyboard.keyDown.listen {
            when (it.key) {
                KEY_ESCAPE -> {
                    // can't yet hook into openrndr exit
                    // instead cleanup on ESC
                    openvrExt.teardown()
                    application.exit()
                }
                KEY_SPACEBAR -> {
                    // can access extension's vr state (OpenVRHMDEvents class) here
                    println(openvr.viewLeft)
                }
            }
        }
    }

    override fun draw() {
        openvrExt.draw(drawer) {
            drawer.background(ColorRGBa.PINK)
            drawer.isolated {
                fill = ColorRGBa.BLACK.opacify(0.5)
                stroke = null
                // 10 meter circle 10 meter below you
                drawer.rotate(Vector3.UNIT_X, 90.0)
                drawer.translate(0.0, 0.0, 10.0)
                drawer.circle(Vector2(0.0, 0.0), 10.0)

            }
        }
    }
}

fun main(args: Array<String>) {
    // square dims because we are rendering a mirror of one of the eyes and we want to match aspect ratio
    // the Vive at least appears the have an AR of 1
    Application.run(VRExample(), configuration {
        width = 1024
        height = 1024
    })
}
```