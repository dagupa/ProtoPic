package com.dagupa.protopic;

import android.content.ContentResolver;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@CapacitorPlugin(name = "MediaScanner")
public class MediaScannerPlugin extends Plugin {

    private static final String TAG = "ProtoPic";

    private File getTargetFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Prototipos");
        }
        path = path.trim().replace('\\', '/');
        if (path.startsWith("/")) {
            return new File(path);
        }
        if (path.startsWith("DCIM/") || path.equals("DCIM")) {
            return new File(Environment.getExternalStorageDirectory(), path);
        }
        if (path.startsWith("Documents/") || path.equals("Documents")) {
            return new File(Environment.getExternalStorageDirectory(), path);
        }
        if (path.startsWith("Pictures/") || path.equals("Pictures")) {
            return new File(Environment.getExternalStorageDirectory(), path);
        }
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), path);
    }

    private boolean deleteFileSafely(File file) {
        if (file == null || !file.exists()) return true;
        boolean deleted = file.delete();
        if (!deleted) {
            try {
                ContentResolver cr = getContext().getContentResolver();
                int r1 = cr.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.MediaColumns.DATA + "=?", new String[]{ file.getAbsolutePath() });
                int r2 = cr.delete(MediaStore.Files.getContentUri("external"),
                        MediaStore.MediaColumns.DATA + "=?", new String[]{ file.getAbsolutePath() });
                deleted = (r1 > 0 || r2 > 0 || !file.exists());
            } catch (Exception ignored) {}
        }
        return deleted;
    }

    // Recupera de forma transparente fotos que se hayan quedado con nombres temporales
    private void recoverTempFiles(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] allFiles = dir.listFiles();
        if (allFiles == null) return;
        List<String> recoveredPaths = new ArrayList<>();
        for (File f : allFiles) {
            String name = f.getName();
            if ((name.startsWith("_ptmp_") || name.contains("_tmp_") || name.endsWith(".tmp")) && f.isFile() && f.length() > 0) {
                // Deduce el prefijo o usa el del archivo
                String prefix = "PASO";
                if (name.contains("_tmp_")) {
                    prefix = name.substring(0, name.indexOf("_tmp_"));
                }
                int nextNum = 1;
                File target;
                do {
                    target = new File(dir, String.format("%s_%03d.jpg", prefix, nextNum));
                    nextNum++;
                } while (target.exists());

                if (f.renameTo(target)) {
                    recoveredPaths.add(target.getAbsolutePath());
                }
            }
        }
        if (!recoveredPaths.isEmpty()) {
            MediaScannerConnection.scanFile(getContext(), recoveredPaths.toArray(new String[0]), null, null);
        }
    }

    @PluginMethod
    public void scanPath(PluginCall call) {
        try {
            String subPath = call.getString("path", "DCIM/Prototipos");
            File target = getTargetFile(subPath);
            scanFileOrDirectory(target);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void scanAll(PluginCall call) {
        try {
            File prototipos = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Prototipos");
            scanFileOrDirectory(prototipos);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void listFiles(PluginCall call) {
        try {
            String path = call.getString("path", "DCIM/Prototipos");
            File dir = getTargetFile(path);
            if (dir.exists() && dir.isDirectory()) {
                recoverTempFiles(dir);
            }

            JSArray arr = new JSArray();
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        JSObject item = new JSObject();
                        item.put("name", f.getName());
                        item.put("isDirectory", f.isDirectory());
                        item.put("size", f.length());
                        arr.put(item);
                    }
                }
            }
            JSObject ret = new JSObject();
            ret.put("files", arr);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Error in listFiles", e);
            call.reject("Error listFiles: " + e.getMessage());
        }
    }

    @PluginMethod
    public void readFile(PluginCall call) {
        try {
            String path = call.getString("path");
            if (path == null) {
                call.reject("Path is required");
                return;
            }
            File file = getTargetFile(path);
            if (!file.exists() || !file.isFile()) {
                call.reject("File does not exist: " + path);
                return;
            }
            String encoding = call.getString("encoding", "base64");
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                int read = 0;
                while (read < bytes.length) {
                    int r = fis.read(bytes, read, bytes.length - read);
                    if (r == -1) break;
                    read += r;
                }
            }
            JSObject ret = new JSObject();
            if ("utf8".equalsIgnoreCase(encoding)) {
                ret.put("data", new String(bytes, StandardCharsets.UTF_8));
            } else {
                ret.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
            }
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Error in readFile", e);
            call.reject("Error readFile: " + e.getMessage());
        }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        try {
            String path = call.getString("path");
            if (path == null) {
                call.reject("Path is required");
                return;
            }
            String data = call.getString("data", "");
            String encoding = call.getString("encoding", "base64");
            File file = getTargetFile(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            try (FileOutputStream fos = new FileOutputStream(file)) {
                if ("utf8".equalsIgnoreCase(encoding)) {
                    fos.write(data.getBytes(StandardCharsets.UTF_8));
                } else {
                    byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                    fos.write(bytes);
                }
            }

            MediaScannerConnection.scanFile(getContext(), new String[]{ file.getAbsolutePath() }, null, null);

            JSObject ret = new JSObject();
            ret.put("uri", file.getAbsolutePath());
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Error in writeFile", e);
            call.reject("Error writeFile: " + e.getMessage());
        }
    }

    @PluginMethod
    public void mkdir(PluginCall call) {
        try {
            String path = call.getString("path");
            if (path == null) {
                call.reject("Path is required");
                return;
            }
            File dir = getTargetFile(path);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            MediaScannerConnection.scanFile(getContext(), new String[]{ dir.getAbsolutePath() }, null, null);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Error in mkdir", e);
            call.reject("Error mkdir: " + e.getMessage());
        }
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        try {
            String path = call.getString("path");
            if (path == null) {
                call.reject("Path is required");
                return;
            }
            File file = getTargetFile(path);
            boolean deleted = deleteFileSafely(file);

            MediaScannerConnection.scanFile(getContext(), new String[]{ file.getAbsolutePath() }, null, null);

            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("deleted", deleted);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Error in deleteFile", e);
            call.reject("Error deleteFile: " + e.getMessage());
        }
    }

    @PluginMethod
    public void renameFile(PluginCall call) {
        try {
            String from = call.getString("from");
            String to = call.getString("to");
            if (from == null || to == null) {
                call.reject("Rutas de origen y destino requeridas");
                return;
            }
            File fromFile = getTargetFile(from);
            File toFile = getTargetFile(to);

            if (!fromFile.exists()) {
                call.reject("Archivo origen no existe: " + fromFile.getName());
                return;
            }

            File parent = toFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            if (toFile.exists() && !toFile.equals(fromFile)) {
                deleteFileSafely(toFile);
            }

            boolean renamed = fromFile.renameTo(toFile);
            if (!renamed) {
                copyFileAtomic(fromFile, toFile);
                deleteFileSafely(fromFile);
                renamed = toFile.exists();
            }

            MediaScannerConnection.scanFile(getContext(),
                    new String[]{ fromFile.getAbsolutePath(), toFile.getAbsolutePath() },
                    null, null);

            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("renamed", renamed);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Error in renameFile", e);
            call.reject("Error renumerando: " + e.getMessage());
        }
    }

    // Reordenación atómica, segura y sin riesgo de pérdida de archivos
    @PluginMethod
    public void reorderFiles(PluginCall call) {
        try {
            String dirPath = call.getString("dir");
            String prefix = call.getString("prefix");
            JSArray filesArray = call.getArray("files");

            if (dirPath == null || prefix == null || filesArray == null) {
                call.reject("dir, prefix y files son requeridos");
                return;
            }

            File directory = getTargetFile(dirPath);
            if (!directory.exists() || !directory.isDirectory()) {
                call.reject("Directorio no encontrado: " + directory.getAbsolutePath());
                return;
            }

            int count = filesArray.length();
            if (count <= 1) {
                JSObject r = new JSObject();
                r.put("success", true);
                call.resolve(r);
                return;
            }

            // 1. Validar que todos los archivos existen en disco antes de tocar nada
            List<File> sourceFiles = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String fname = filesArray.getString(i);
                if (fname == null || fname.trim().isEmpty()) {
                    call.reject("Nombre inválido en el índice " + i);
                    return;
                }
                File f = new File(directory, fname.trim());
                if (!f.exists()) {
                    call.reject("Falta el archivo origen: " + fname);
                    return;
                }
                sourceFiles.add(f);
            }

            // 2. Paso 1: Mover cada uno a un nombre temporal único terminando en .jpg
            long ts = System.currentTimeMillis();
            List<File> tempFiles = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                File src = sourceFiles.get(i);
                File tmp = new File(directory, "_ptmp_" + ts + "_" + i + ".jpg");
                boolean ok = src.renameTo(tmp);
                if (!ok) {
                    copyFileAtomic(src, tmp);
                    src.delete();
                }
                tempFiles.add(tmp);
            }

            // 3. Paso 2: Asignar el nuevo número secuencial a cada foto
            List<String> pathsToScan = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                File tmp = tempFiles.get(i);
                String newName = String.format("%s_%03d.jpg", prefix, (i + 1));
                File dest = new File(directory, newName);
                if (dest.exists() && !dest.equals(tmp)) {
                    dest.delete();
                }
                boolean ok = tmp.renameTo(dest);
                if (!ok) {
                    copyFileAtomic(tmp, dest);
                    tmp.delete();
                }
                pathsToScan.add(dest.getAbsolutePath());
            }

            // 4. Notificar a Windows por MediaScanner
            if (!pathsToScan.isEmpty()) {
                MediaScannerConnection.scanFile(getContext(), pathsToScan.toArray(new String[0]), null, null);
            }

            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("count", count);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Error in reorderFiles", e);
            call.reject("Error al reordenar: " + e.getMessage());
        }
    }

    @PluginMethod
    public void writeNote(PluginCall call) {
        try {
            String proj = call.getString("project", "Sin_Proyecto");
            String op = call.getString("operation", "General");
            String text = call.getString("text", "");

            List<File> targetDirs = new ArrayList<>();
            targetDirs.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Prototipos/" + proj));
            targetDirs.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Prototipos/" + proj));
            File appExt = getContext().getExternalFilesDir(null);
            if (appExt != null) {
                targetDirs.add(new File(appExt, "Prototipos/" + proj));
            }

            List<String> pathsToScan = new ArrayList<>();
            boolean written = false;

            for (File dir : targetDirs) {
                try {
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    // 1. Archivo plano: [op]_notas.txt
                    File flatFile = new File(dir, op + "_notas.txt");
                    try (FileOutputStream fos = new FileOutputStream(flatFile)) {
                        fos.write(text.getBytes(StandardCharsets.UTF_8));
                        written = true;
                        pathsToScan.add(flatFile.getAbsolutePath());
                    }
                    // 2. Subcarpeta: [op]/notas.txt
                    File subDir = new File(dir, op);
                    if (!subDir.exists()) {
                        subDir.mkdirs();
                    }
                    File subFile = new File(subDir, "notas.txt");
                    try (FileOutputStream fos = new FileOutputStream(subFile)) {
                        fos.write(text.getBytes(StandardCharsets.UTF_8));
                        written = true;
                        pathsToScan.add(subFile.getAbsolutePath());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "writeNote fallback for " + dir.getAbsolutePath() + ": " + e.getMessage());
                }
            }

            if (!pathsToScan.isEmpty()) {
                MediaScannerConnection.scanFile(getContext(), pathsToScan.toArray(new String[0]), null, null);
            }

            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("written", written);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Error in writeNote", e);
            call.reject("Error writeNote: " + e.getMessage());
        }
    }

    @PluginMethod
    public void readNote(PluginCall call) {
        try {
            String proj = call.getString("project", "Sin_Proyecto");
            String op = call.getString("operation", "General");

            List<File> targetDirs = new ArrayList<>();
            targetDirs.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Prototipos/" + proj));
            targetDirs.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Prototipos/" + proj));
            File appExt = getContext().getExternalFilesDir(null);
            if (appExt != null) {
                targetDirs.add(new File(appExt, "Prototipos/" + proj));
            }

            for (File dir : targetDirs) {
                File subFile = new File(dir, op + "/notas.txt");
                if (subFile.exists() && subFile.isFile() && subFile.length() > 0) {
                    String content = readTextFile(subFile);
                    if (content != null && !content.isEmpty()) {
                        JSObject ret = new JSObject();
                        ret.put("data", content);
                        call.resolve(ret);
                        return;
                    }
                }
                File flatFile = new File(dir, op + "_notas.txt");
                if (flatFile.exists() && flatFile.isFile() && flatFile.length() > 0) {
                    String content = readTextFile(flatFile);
                    if (content != null && !content.isEmpty()) {
                        JSObject ret = new JSObject();
                        ret.put("data", content);
                        call.resolve(ret);
                        return;
                    }
                }
            }

            JSObject ret = new JSObject();
            ret.put("data", "");
            call.resolve(ret);
        } catch (Exception e) {
            JSObject ret = new JSObject();
            ret.put("data", "");
            call.resolve(ret);
        }
    }

    @PluginMethod
    public void deleteNote(PluginCall call) {
        try {
            String proj = call.getString("project", "Sin_Proyecto");
            String op = call.getString("operation", "General");

            List<File> targetDirs = new ArrayList<>();
            targetDirs.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Prototipos/" + proj));
            targetDirs.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Prototipos/" + proj));
            File appExt = getContext().getExternalFilesDir(null);
            if (appExt != null) {
                targetDirs.add(new File(appExt, "Prototipos/" + proj));
            }

            List<String> pathsToScan = new ArrayList<>();
            for (File dir : targetDirs) {
                File subFile = new File(dir, op + "/notas.txt");
                if (subFile.exists()) {
                    subFile.delete();
                    pathsToScan.add(subFile.getAbsolutePath());
                }
                File flatFile = new File(dir, op + "_notas.txt");
                if (flatFile.exists()) {
                    flatFile.delete();
                    pathsToScan.add(flatFile.getAbsolutePath());
                }
            }

            if (!pathsToScan.isEmpty()) {
                MediaScannerConnection.scanFile(getContext(), pathsToScan.toArray(new String[0]), null, null);
            }

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.resolve(new JSObject());
        }
    }

    private String readTextFile(File file) {
        try {
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                int read = 0;
                while (read < bytes.length) {
                    int r = fis.read(bytes, read, bytes.length - read);
                    if (r == -1) break;
                    read += r;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    @PluginMethod
    public void wipeAll(PluginCall call) {
        try {
            File dcimProto = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Prototipos");
            File docProto = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Prototipos");
            File picProto = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Prototipos");

            List<String> pathsToScan = new ArrayList<>();
            deleteRecursively(dcimProto, pathsToScan);
            deleteRecursively(docProto, pathsToScan);
            deleteRecursively(picProto, pathsToScan);

            // Recrear carpeta DCIM/Prototipos limpia
            if (!dcimProto.exists()) {
                dcimProto.mkdirs();
            }
            pathsToScan.add(dcimProto.getAbsolutePath());

            // Actualizar MediaStore para que desaparezcan de Windows Explorer por MTP
            if (!pathsToScan.isEmpty()) {
                MediaScannerConnection.scanFile(getContext(), pathsToScan.toArray(new String[0]), null, null);
            }

            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("deletedPathsCount", pathsToScan.size());
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Error in wipeAll", e);
            call.reject("Error wipeAll: " + e.getMessage());
        }
    }

    private void deleteRecursively(File fileOrDir, List<String> paths) {
        if (fileOrDir == null || !fileOrDir.exists()) return;
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child, paths);
                }
            }
        }
        paths.add(fileOrDir.getAbsolutePath());
        deleteFileSafely(fileOrDir);
    }

    private void copyFileAtomic(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    public void scanFileOrDirectory(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) return;
        List<String> pathsToScan = new ArrayList<>();
        collectPaths(fileOrDir, pathsToScan);
        if (!pathsToScan.isEmpty()) {
            String[] arr = pathsToScan.toArray(new String[0]);
            MediaScannerConnection.scanFile(getContext(), arr, null, null);
        }
    }

    private void collectPaths(File file, List<String> paths) {
        if (file == null) return;
        paths.add(file.getAbsolutePath());
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    collectPaths(child, paths);
                }
            }
        }
    }
}
