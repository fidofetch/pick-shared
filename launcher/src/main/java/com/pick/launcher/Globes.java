package com.pick.launcher;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL33.*;

// The planets in their cases: each globe drawn lit, with the case's light around it, into one row of square
// pictures (a texture), so ImGui shows them as images in its own drawing order.
//     globes.upload(key, colours)     a planet's surface, once it has been read (on the GL thread)
//     globes.begin(n, sizePx)         then draw(slot, key, spin, light) for each case on screen, then end(fbW, fbH)
//     globes.texture(), uv(slot)      for ImGui.image
final class Globes {
    private int program, vao, fbo, tex, cols, size;
    private final Map<String, int[]> surfaces = new HashMap<>();   // key -> {texture, n}
    private final Map<String, float[][]> axes = new HashMap<>();

    void upload(String key, Planets.Colours c) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 6 * c.n(), c.n(), 0, GL_RGBA, GL_UNSIGNED_BYTE, c.rgba());
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        int[] old = surfaces.put(key, new int[]{t, c.n()});
        if (old != null) glDeleteTextures(old[0]);
        axes.put(key, c.axes());
    }

    boolean has(String key) { return surfaces.containsKey(key); }

    void begin(int n, int sizePx) {
        if (program == 0) program = compile();
        if (vao == 0) vao = glGenVertexArrays();
        sizePx = Math.max(8, sizePx);
        n = Math.max(1, n);
        if (fbo == 0 || n > cols || sizePx != size) make(Math.max(n, cols), sizePx);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, cols * size, size);
        glDisable(GL_SCISSOR_TEST); glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(program);
        glBindVertexArray(vao);
    }

    // One case's picture: the planet (or, with no surface yet, only the case's light), turned by spin radians.
    // light 0..1: how bright the case is lit (the chosen one most).
    void draw(int slot, String key, float spin, float light) {
        glViewport(slot * size, 0, size, size);
        int[] s = key == null ? null : surfaces.get(key);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, s == null ? 0 : s[0]);
        glUniform1i(glGetUniformLocation(program, "faces"), 0);
        glUniform1i(glGetUniformLocation(program, "hasSurface"), s == null ? 0 : 1);
        glUniform1f(glGetUniformLocation(program, "n"), s == null ? 1 : s[1]);
        glUniform1f(glGetUniformLocation(program, "spin"), spin);
        glUniform1f(glGetUniformLocation(program, "light"), light);
        glUniform1f(glGetUniformLocation(program, "px"), size);
        float[][] a = s == null ? null : axes.get(key);
        if (a != null) {
            float[] nr = new float[18], rt = new float[18], up = new float[18];
            for (int f = 0; f < 6; f++) for (int k = 0; k < 3; k++) { nr[f * 3 + k] = a[f][k]; rt[f * 3 + k] = a[f][3 + k]; up[f * 3 + k] = a[f][6 + k]; }
            glUniform3fv(glGetUniformLocation(program, "normal"), nr);
            glUniform3fv(glGetUniformLocation(program, "right"), rt);
            glUniform3fv(glGetUniformLocation(program, "up"), up);
        }
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    void end(int fbW, int fbH) {
        glBindVertexArray(0);
        glUseProgram(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, fbW, fbH);
    }

    int texture() { return tex; }

    // u0, v0, u1, v1 for picture i (the texture's rows run bottom up, ImGui's top down)
    float[] uv(int i) { float w = cols; return new float[]{i / w, 1f, (i + 1) / w, 0f}; }

    private void make(int n, int sizePx) {
        if (fbo != 0) glDeleteFramebuffers(fbo);
        if (tex != 0) glDeleteTextures(tex);
        cols = n; size = sizePx;
        tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, cols * size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) System.err.println("[globes] framebuffer not complete");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private static final String VERT = """
            #version 330 core
            out vec2 p;
            void main() {
                vec2 c = vec2((gl_VertexID & 1) == 0 ? -1.0 : 1.0, (gl_VertexID & 2) == 0 ? -1.0 : 1.0);
                p = c;
                gl_Position = vec4(c, 0.0, 1.0);
            }
            """;

    // The globe fills 62% of the case. Around it the case's own light: warm, strongest just behind the planet,
    // fading out before the case's edge. The surface is the package's cube-sphere grid (equiangular faces).
    private static final String FRAG = """
            #version 330 core
            in vec2 p;
            out vec4 colour;
            uniform sampler2D faces;
            uniform int hasSurface;
            uniform float n, spin, light, px;
            uniform vec3 normal[6], right[6], up[6];
            const float R = 0.62;
            const float QUARTER = 0.78539816;
            const vec3 WARM = vec3(1.0, 0.74, 0.40);

            vec3 surface(vec3 d) {
                int f = 0; float best = -2.0;
                for (int k = 0; k < 6; k++) { float s = dot(d, normal[k]); if (s > best) { best = s; f = k; } }
                float a = dot(d, right[f]) / best, b = dot(d, up[f]) / best;
                float i = (atan(a) / QUARTER + 1.0) * 0.5, j = (atan(b) / QUARTER + 1.0) * 0.5;
                float e = 0.5 / n;
                i = clamp(i, e, 1.0 - e); j = clamp(j, e, 1.0 - e);
                return texture(faces, vec2((float(f) + i) / 6.0, j)).rgb;
            }

            void main() {
                float r = length(p) / R;
                // the case's light, behind and around the planet
                float glow = light * (0.38 * exp(-max(r - 1.0, 0.0) * 4.5) + 0.07 * exp(-r * r * 0.6));
                glow *= smoothstep(1.0, 0.82, length(p));   // gone before the case's edge
                vec4 back = vec4(WARM * glow, clamp(glow, 0.0, 1.0));
                if (hasSurface == 0 || r >= 1.0) { colour = vec4(back.rgb / max(back.a, 1e-4), back.a); return; }

                vec2 q = p / R;
                float z = sqrt(max(0.0, 1.0 - dot(q, q)));
                vec3 v = vec3(q, z);   // the view: x right, y up, z towards us
                float t = 0.36, ct = cos(t), st = sin(t);   // the axis leans a little, like a desk globe
                v = vec3(ct * v.x - st * v.y, st * v.x + ct * v.y, v.z);
                vec3 w = vec3(v.x, -v.z, v.y);   // the planet's axis (+Z) points up the picture
                float cs = cos(spin), sn = sin(spin);
                w = vec3(cs * w.x - sn * w.y, sn * w.x + cs * w.y, w.z);

                vec3 c = surface(normalize(w));
                vec3 L = normalize(vec3(-0.45, 0.55, 0.70));
                float diff = max(dot(vec3(q, z), L), 0.0);
                vec3 lit = c * (0.16 + 0.95 * diff);
                lit += WARM * pow(1.0 - z, 2.5) * 0.30 * light;   // the case's light through the planet's air, at its rim
                float edge = smoothstep(1.0, 1.0 - 2.5 / (px * R * 0.5), r);   // a soft, anti-aliased edge
                vec3 rgb = mix(back.rgb / max(back.a, 1e-4), lit, edge);
                colour = vec4(rgb, max(edge, back.a));
            }
            """;

    private static int compile() {
        int vs = shader(GL_VERTEX_SHADER, VERT), fs = shader(GL_FRAGMENT_SHADER, FRAG);
        int p = glCreateProgram();
        glAttachShader(p, vs); glAttachShader(p, fs);
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == 0) throw new IllegalStateException("globe shader: " + glGetProgramInfoLog(p));
        glDeleteShader(vs); glDeleteShader(fs);
        return p;
    }

    private static int shader(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == 0) throw new IllegalStateException("globe shader: " + glGetShaderInfoLog(s));
        return s;
    }
}
