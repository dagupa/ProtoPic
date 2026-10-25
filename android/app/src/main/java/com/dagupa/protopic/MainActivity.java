package com.dagupa.protopic;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaScannerPlugin.class);
        super.onCreate(savedInstanceState);

        List<String> perms = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!perms.isEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), 101);
        }

        scanPrototipos();
    }

    @Override
    public void onResume() {
        super.onResume();
        scanPrototipos();
    }

    public void scanPrototipos() {
        try {
            File prototiposDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Prototipos");
            if (!prototiposDir.exists()) {
                prototiposDir.mkdirs();
            }

            List<String> paths = new ArrayList<>();
            collectPaths(prototiposDir, paths);
            if (!paths.isEmpty()) {
                MediaScannerConnection.scanFile(this, paths.toArray(new String[0]), null, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
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
