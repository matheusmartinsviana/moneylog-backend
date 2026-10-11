package com.moneylog.qa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.ContractValidation;

final class NoteQa {

    private NoteQa() {
    }

    static String json(CardQaWorld w, String text) throws Exception {
        return w.json.writeValueAsString(text);
    }

    static Res create(CardQaWorld w, Actor a, String path, String body) throws Exception {
        return a.call(HttpMethod.POST, "/v1/notes", "{\"path\":" + json(w, path) + ",\"body\":" + json(w, body) + "}", null);
    }

    static Res createWithLinks(CardQaWorld w, Actor a, String path, String body, String linksJson) throws Exception {
        return a.call(HttpMethod.POST, "/v1/notes", "{\"path\":" + json(w, path) + ",\"body\":" + json(w, body) + ",\"links\":" + linksJson + "}", null);
    }

    static String createOk(CardQaWorld w, Actor a, String path, String body) throws Exception {
        return CardQaWorld.ok(create(w, a, path, body)).node().get("id").asString();
    }

    static Res edit(CardQaWorld w, Actor a, String id, String body, long version) throws Exception {
        return a.call(HttpMethod.PUT, "/v1/notes/" + id, "{\"body\":" + json(w, body) + ",\"version\":" + version + "}", null);
    }

    static Res move(CardQaWorld w, Actor a, String id, String newPath, long version) throws Exception {
        return a.call(HttpMethod.POST, "/v1/notes/" + id + "/move", "{\"newPath\":" + json(w, newPath) + ",\"version\":" + version + "}", null);
    }

    static Res trash(Actor a, String id) throws Exception {
        return a.call(HttpMethod.POST, "/v1/notes/" + id + "/trash", null, null);
    }

    static Res restore(Actor a, String id) throws Exception {
        return a.call(HttpMethod.POST, "/v1/notes/" + id + "/restore", null, null);
    }

    static Res setLinks(Actor a, String id, String linksJson, long version) throws Exception {
        return a.call(HttpMethod.PUT, "/v1/notes/" + id + "/links", "{\"links\":" + linksJson + ",\"version\":" + version + "}", null);
    }

    static JsonNode get(Actor a, String id) throws Exception {
        return a.getOk("/v1/notes/" + id);
    }

    static String link(String type, String id) {
        return "{\"type\":\"" + type + "\",\"id\":\"" + id + "\"}";
    }

    static String monthLink(String key) {
        return "{\"type\":\"MONTHLY_SUMMARY\",\"key\":\"" + key + "\"}";
    }

    static Res upload(CardQaWorld w, Actor a, byte[] zip, String mode) throws Exception {
        MockMultipartHttpServletRequestBuilder b = MockMvcRequestBuilders.multipart("/v1/vault/import")
                .file(new MockMultipartFile("file", "vault.zip", "application/zip", zip)).header(HttpHeaders.AUTHORIZATION, a.bearer);
        if (mode != null) {
            b.param("mode", mode);
        }
        return finish(w, w.mvc.perform(b).andReturn(), "POST /v1/vault/import");
    }

    static Res finish(CardQaWorld w, MvcResult r, String what) throws Exception {
        try {
            ContractValidation.responseOnly().match(r);
        } catch (AssertionError | Exception e) {
            w.contractViolations.add(what + " -> " + r.getResponse().getStatus() + ": " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
        }
        return w.res(r);
    }

    static byte[] export(CardQaWorld w, Actor a) throws Exception {
        MvcResult r = w.mvc.perform(MockMvcRequestBuilders.get("/v1/vault/export").header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn();
        if (r.getResponse().getStatus() != 200) {
            throw new AssertionError("export -> " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        }
        return r.getResponse().getContentAsByteArray();
    }

    static final class RawZip {

        private record Item(byte[] name, byte[] data, boolean deflate, boolean utf8Flag, int externalAttrs) {
        }

        private final List<Item> items = new ArrayList<>();
        private boolean centralDirectory = true;

        RawZip add(String name, String content) {
            return add(name.getBytes(StandardCharsets.UTF_8), content.getBytes(StandardCharsets.UTF_8), true, true, 0);
        }

        RawZip add(String name, byte[] content) {
            return add(name.getBytes(StandardCharsets.UTF_8), content, true, true, 0);
        }

        RawZip addRaw(byte[] name, byte[] content, boolean utf8Flag) {
            return add(name, content, true, utf8Flag, 0);
        }

        RawZip symlink(String name, String target) {
            return add(name.getBytes(StandardCharsets.UTF_8), target.getBytes(StandardCharsets.UTF_8), false, true, (0xA1FF) << 16);
        }

        RawZip add(byte[] name, byte[] data, boolean deflate, boolean utf8Flag, int externalAttrs) {
            items.add(new Item(name, data, deflate, utf8Flag, externalAttrs));
            return this;
        }

        RawZip withoutCentralDirectory() {
            centralDirectory = false;
            return this;
        }

        RawZip addZeros(String name, long size) {
            byte[] compressed = deflateZeros(size);
            CRC32 crc = new CRC32();
            byte[] chunk = new byte[1 << 16];
            long left = size;
            while (left > 0) {
                int n = (int) Math.min(chunk.length, left);
                crc.update(chunk, 0, n);
                left -= n;
            }
            zerosEntries.add(new Object[] {name.getBytes(StandardCharsets.UTF_8), compressed, crc.getValue(), size});
            return this;
        }

        private final List<Object[]> zerosEntries = new ArrayList<>();

        private static byte[] deflateZeros(long size) {
            Deflater deflater = new Deflater(9, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] zeros = new byte[1 << 16];
            byte[] buffer = new byte[1 << 14];
            long left = size;
            while (left > 0) {
                int n = (int) Math.min(zeros.length, left);
                deflater.setInput(zeros, 0, n);
                while (!deflater.needsInput()) {
                    out.write(buffer, 0, deflater.deflate(buffer));
                }
                left -= n;
            }
            deflater.finish();
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            deflater.end();
            return out.toByteArray();
        }

        byte[] bytes() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<int[]> offsets = new ArrayList<>();
            List<Object[]> central = new ArrayList<>();
            try {
                for (Item item : items) {
                    byte[] payload = item.deflate() ? deflate(item.data()) : item.data();
                    CRC32 crc = new CRC32();
                    crc.update(item.data());
                    int offset = out.size();
                    writeLocal(out, item.name(), payload, crc.getValue(), item.data().length, item.deflate() ? 8 : 0, item.utf8Flag());
                    central.add(new Object[] {item.name(), crc.getValue(), payload.length, (long) item.data().length, item.deflate() ? 8 : 0, item.utf8Flag(),
                            item.externalAttrs(), offset});
                }
                for (Object[] z : zerosEntries) {
                    int offset = out.size();
                    byte[] payload = (byte[]) z[1];
                    writeLocal(out, (byte[]) z[0], payload, (Long) z[2], (Long) z[3], 8, true);
                    central.add(new Object[] {z[0], z[2], payload.length, z[3], 8, true, 0, offset});
                }
                if (centralDirectory) {
                    int start = out.size();
                    for (Object[] c : central) {
                        byte[] name = (byte[]) c[0];
                        ByteBuffer b = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
                        b.putInt(0x02014b50).putShort((short) ((3 << 8) | 20)).putShort((short) 20).putShort((short) ((Boolean) c[5] ? 0x0800 : 0))
                                .putShort((short) (int) c[4]).putShort((short) 0).putShort((short) 0x21).putInt((int) (long) (Long) c[1])
                                .putInt((int) c[2]).putInt((int) (long) (Long) c[3]).putShort((short) name.length).putShort((short) 0).putShort((short) 0)
                                .putShort((short) 0).putShort((short) 0).putInt((int) c[6]).putInt((int) c[7]);
                        out.write(b.array());
                        out.write(name);
                    }
                    int size = out.size() - start;
                    ByteBuffer e = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
                    e.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) central.size()).putShort((short) central.size()).putInt(size)
                            .putInt(start).putShort((short) 0);
                    out.write(e.array());
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return out.toByteArray();
        }

        private static void writeLocal(ByteArrayOutputStream out, byte[] name, byte[] payload, long crc, long usize, int method, boolean utf8) throws IOException {
            ByteBuffer b = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(0x04034b50).putShort((short) 20).putShort((short) (utf8 ? 0x0800 : 0)).putShort((short) method).putShort((short) 0).putShort((short) 0x21)
                    .putInt((int) crc).putInt(payload.length).putInt((int) usize).putShort((short) name.length).putShort((short) 0);
            out.write(b.array());
            out.write(name);
            out.write(payload);
        }

        private static byte[] deflate(byte[] data) {
            Deflater deflater = new Deflater(6, true);
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            deflater.end();
            return out.toByteArray();
        }
    }
}
