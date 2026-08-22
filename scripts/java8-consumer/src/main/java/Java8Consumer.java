import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import no.synth.kmpzip.zip.ZipEntry;
import no.synth.kmpzip.zip.ZipInputStream;
import no.synth.kmpzip.zip.ZipOutputStream;

public class Java8Consumer {
    public static void main(String[] args) throws Exception {
        byte[] payload = "hello from java 8".getBytes(StandardCharsets.UTF_8);
        byte[] password = "hunter2".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(archive, password);
        zos.putNextEntry(new ZipEntry("hello.txt"));
        zos.write(payload);
        zos.close();

        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()), password);
        ZipEntry entry = zis.getNextEntry();
        if (entry == null || !"hello.txt".equals(entry.getName())) {
            throw new AssertionError("unexpected entry: " + (entry == null ? "null" : entry.getName()));
        }
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        byte[] chunk = new byte[512];
        int n;
        while ((n = zis.read(chunk)) != -1) {
            read.write(chunk, 0, n);
        }
        if (zis.getNextEntry() != null) {
            throw new AssertionError("expected a single entry");
        }
        zis.close();

        if (!Arrays.equals(payload, read.toByteArray())) {
            throw new AssertionError("round trip mismatch");
        }
        System.out.println("OK: kmp-zip AES round trip on Java " + System.getProperty("java.version"));
    }
}
