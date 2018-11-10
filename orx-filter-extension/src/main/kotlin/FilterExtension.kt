package org.openrndr.extra.filterextension

import org.openrndr.Extension
import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*

/**
 * Extends the [Program] with a [Filter]. This is achieved by wrapping the Filter in an Extension.
 */
fun <F : Filter> Program.extend(filter: F, configuration: F.() -> Unit = {}): Extension {

    val filterExtension = object : Extension {
        override var enabled: Boolean = true

        var renderTarget: RenderTarget? = null
        var filtered: ColorBuffer? = null
        override fun beforeDraw(drawer: Drawer, program: Program) {
            if (renderTarget == null || renderTarget?.width != program.width || renderTarget?.height != program.height) {
                renderTarget?.let {
                    it.colorBuffer(0).destroy()
                    it.detachColorBuffers()
                    it.destroy()
                }

                filtered?.destroy()
                renderTarget = renderTarget(program.width, program.height) {
                    colorBuffer()
                    depthBuffer()
                }

                filtered = colorBuffer(program.width, program.height)

                renderTarget?.let {
                    drawer.withTarget(it) {
                        background(program.backgroundColor ?: ColorRGBa.TRANSPARENT)
                    }
                }
            }
            renderTarget?.bind()

        }

        override fun afterDraw(drawer: Drawer, program: Program) {
            renderTarget?.unbind()

            filter.configuration()
            renderTarget?.let {
                filtered?.let { filtered ->
                    drawer.isolated {
                        drawer.ortho()
                        filter.apply(it.colorBuffer(0), filtered)
                        drawer.image(filtered)
                    }
                }
            }
        }
    }

    return extend(filterExtension)
}