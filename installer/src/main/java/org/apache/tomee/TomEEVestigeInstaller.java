package org.apache.tomee;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.IOUtils;

/**
 * @author gaellalire
 */
public class TomEEVestigeInstaller {

    private File base;

    public TomEEVestigeInstaller(final File base) {
        this.base = base;
    }

    public void install() throws Exception {
        ZipInputStream zipFile = new ZipInputStream(TomEEVestigeInstaller.class.getResourceAsStream("/home.zip"));
        ZipEntry entry = zipFile.getNextEntry();
        while (entry != null) {
            File entryDestination = new File(base, entry.getName());
            if (entry.isDirectory()) {
                entryDestination.mkdirs();
            } else {
                entryDestination.getParentFile().mkdirs();
                OutputStream out = new FileOutputStream(entryDestination);
                IOUtils.copy(zipFile, out);
                IOUtils.closeQuietly(out);
            }
            zipFile.closeEntry();
            entry = zipFile.getNextEntry();
        }
        File apps = new File(base, "apps");
        apps.mkdirs();
        File entryDestination = new File(apps, "myear.vear");
        OutputStream out = new FileOutputStream(entryDestination);
        IOUtils.copy(TomEEVestigeInstaller.class.getResourceAsStream("/myear.vear"), out);
        IOUtils.closeQuietly(out);

        entryDestination = new File(apps, "mywar.vwar");
        out = new FileOutputStream(entryDestination);
        IOUtils.copy(TomEEVestigeInstaller.class.getResourceAsStream("/mywar.vwar"), out);
        IOUtils.closeQuietly(out);

        out = new FileOutputStream(new File(base, "conf" + File.separator + "server.xml"));
        IOUtils.copy(TomEEVestigeInstaller.class.getResourceAsStream("/server.xml"), out);
        IOUtils.closeQuietly(out);

        out = new FileOutputStream(new File(base, "conf" + File.separator + "tomee.xml"));
        IOUtils.copy(TomEEVestigeInstaller.class.getResourceAsStream("/tomee.xml"), out);
        IOUtils.closeQuietly(out);
    }

}
