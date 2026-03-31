import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class ExtractZip {
    public static void main(String[] args) throws Exception {
        // D:\황민국\코인\패턴\pattern.zip (Unicode escaped)
        String src = "D:\\\ud669\ubbfc\uad6d\\\ucf54\uc778\\\ud328\ud134\\pattern.zip";
        String dst = "C:\\workspace\\upbit-autotrade-java8\\upbit-autotrade\\data\\patterns\\extracted";

        File dstDir = new File(dst);
        if (dstDir.exists()) deleteDir(dstDir);
        dstDir.mkdirs();

        System.out.println("Source: " + src);
        System.out.println("Exists: " + new File(src).exists());

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(src), java.nio.charset.Charset.forName("UTF-8"))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                File f = new File(dst, name);
                System.out.println((entry.isDirectory() ? "[DIR] " : "[FILE] ") + name + " (" + entry.getSize() + " bytes)");
                if (entry.isDirectory()) {
                    f.mkdirs();
                } else {
                    f.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                }
                zis.closeEntry();
            }
        }
        System.out.println("DONE");
    }

    static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
        dir.delete();
    }
}
