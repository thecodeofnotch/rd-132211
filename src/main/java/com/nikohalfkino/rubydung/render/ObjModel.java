package com.nikohalfkino.rubydung.render;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * a triangle mesh read from a Wavefront .obj in the resources: what every 3D model of the game is loaded with (the ragdolls now, the player models later)
 *
 * a vertex here is a position with its own texture coordinate, so a position the .obj uses with two different coordinates is two vertices. faces with more than three corners are cut into triangles. the texture's v is turned (obj has it from the bottom, the textures are read from the top). the normals of the file are ignored: every triangle gets a flat one, which is all the shading of the game needs. materials are ignored, the texture is picked by whoever loads the model
 *
 * it is plain data: no OpenGL, so it can be read on any thread
 */
public final class ObjModel {

    /** x, y, z of every vertex, in the model's own units */
    public final float[] positions;
    /** u, v of every vertex */
    public final float[] uvs;
    /** three vertex numbers per triangle, counter-clockwise seen from outside */
    public final int[] triangles;
    /** the unit normal of every triangle (x, y, z) as it was modelled, before any bending */
    public final float[] normals;

    private ObjModel(float[] positions, float[] uvs, int[] triangles, float[] normals) {
        this.positions = positions;
        this.uvs = uvs;
        this.triangles = triangles;
        this.normals = normals;
    }

    public int vertexCount() {
        return this.positions.length / 3;
    }

    public int triangleCount() {
        return this.triangles.length / 3;
    }

    /** reads the .obj at a classpath resource, e.g. "/assets/rubydung/models/ragdolls/cheaple/male_cheaple.obj". throws a RuntimeException if it is missing or has no triangles */
    public static ObjModel load(String resource) {
        try (InputStream in = ObjModel.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new RuntimeException("Could not find model " + resource);
            }
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), resource);
        } catch (IOException e) {
            throw new RuntimeException("Could not read model " + resource, e);
        }
    }

    private static ObjModel parse(BufferedReader reader, String name) throws IOException {
        List<float[]> v = new ArrayList<>();
        List<float[]> vt = new ArrayList<>();

        List<Float> outPos = new ArrayList<>();
        List<Float> outUv = new ArrayList<>();
        List<Integer> outTri = new ArrayList<>();
        Map<Long, Integer> unique = new HashMap<>();

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            String[] p = line.split("\\s+");
            switch (p[0]) {
                case "v":
                    v.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                    break;
                case "vt":
                    vt.add(new float[]{Float.parseFloat(p[1]), p.length > 2 ? Float.parseFloat(p[2]) : 0.0F});
                    break;
                case "f": {
                    int corners = p.length - 1;
                    int[] ids = new int[corners];
                    for (int i = 0; i < corners; i++) {
                        String[] part = p[i + 1].split("/");
                        int vi = index(part[0], v.size());
                        int ti = part.length > 1 && !part[1].isEmpty() ? index(part[1], vt.size()) : -1;
                        long key = ((long) vi << 32) | (ti & 0xFFFFFFFFL);
                        Integer id = unique.get(key);
                        if (id == null) {
                            id = outPos.size() / 3;
                            unique.put(key, id);
                            float[] pos = v.get(vi);
                            outPos.add(pos[0]);
                            outPos.add(pos[1]);
                            outPos.add(pos[2]);
                            float[] uv = ti >= 0 ? vt.get(ti) : new float[]{0.0F, 0.0F};
                            outUv.add(uv[0]);
                            outUv.add(1.0F - uv[1]);
                        }
                        ids[i] = id;
                    }
                    for (int i = 1; i + 1 < corners; i++) {   // a fan from the first corner
                        outTri.add(ids[0]);
                        outTri.add(ids[i]);
                        outTri.add(ids[i + 1]);
                    }
                    break;
                }
                default:
                    break;   // o, g, s, usemtl, mtllib, vn: nothing the game uses
            }
        }
        if (outTri.isEmpty()) {
            throw new RuntimeException("Model " + name + " has no triangles");
        }

        float[] positions = toFloats(outPos);
        int[] triangles = new int[outTri.size()];
        for (int i = 0; i < triangles.length; i++) {
            triangles[i] = outTri.get(i);
        }
        return new ObjModel(positions, toFloats(outUv), triangles, flatNormals(positions, triangles));
    }

    // obj numbers start at 1, a negative one counts back from the last read
    private static int index(String text, int count) {
        int i = Integer.parseInt(text);
        return i > 0 ? i - 1 : count + i;
    }

    private static float[] toFloats(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static float[] flatNormals(float[] pos, int[] tri) {
        float[] n = new float[tri.length];
        for (int t = 0; t < tri.length; t += 3) {
            int a = tri[t] * 3, b = tri[t + 1] * 3, c = tri[t + 2] * 3;
            float ux = pos[b] - pos[a], uy = pos[b + 1] - pos[a + 1], uz = pos[b + 2] - pos[a + 2];
            float wx = pos[c] - pos[a], wy = pos[c + 1] - pos[a + 1], wz = pos[c + 2] - pos[a + 2];
            float nx = uy * wz - uz * wy;
            float ny = uz * wx - ux * wz;
            float nz = ux * wy - uy * wx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1.0E-12F) {
                n[t + 1] = 1.0F;   // a triangle with no area is never seen, any normal will do
                continue;
            }
            n[t] = nx / len;
            n[t + 1] = ny / len;
            n[t + 2] = nz / len;
        }
        return n;
    }
}
