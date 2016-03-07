package example.com.trymediacodec;

import android.opengl.GLES20;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import java.io.File;

public class MainActivity extends AppCompatActivity implements Mpeg4Recorder.ICallback {
    private static final String TAG = MainActivity.class.getName();
    private Mpeg4Recorder _recorder = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        File outputDir = Environment.getExternalStorageDirectory();
        String path = new File(outputDir, "hoge.mp4").toString();
        Log.d(TAG, "path: " + path);

        _recorder= new Mpeg4Recorder(path, 320, 240, 15, this);
        if (!_recorder.prepare()){
            Log.e(TAG, "Mpeg4Recorder#parepare(...) was failed.");
        } else {
            _recorder.start();
        }
    }

    @Override
    public void onDrawOnEgl() {
        GLES20.glClearColor(0.0f, 0.5f, 0.1f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
