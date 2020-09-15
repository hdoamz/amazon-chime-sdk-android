package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl


private val FRAGMENT_SHADER: String =
"""
void main() {
  gl_FragColor = sample(tc);
}
"""
/** Simplest possible GL shader that just draws frames as opaque quads.  */
class GlRectDrawer :  GlGenericDrawer(FRAGMENT_SHADER, GlRectDrawer.ShaderCallbacks()) {

    private class ShaderCallbacks :
        GlGenericDrawer.ShaderCallbacks {
        override fun onNewShader(shader: GlShader?) {}
        override fun onPrepareShader(
            shader: GlShader?,
            texMatrix: FloatArray?,
            frameWidth: Int,
            frameHeight: Int,
            viewportWidth: Int,
            viewportHeight: Int
        ) {
        }
    }
}
