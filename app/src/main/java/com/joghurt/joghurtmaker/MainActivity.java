package com.joghurt.joghurtmaker;

import android.content.Context;
import android.media.MediaPlayer;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;

public class MainActivity extends AppCompatActivity {

    private TemperReadThread temperReadThread = null;
    private TextView textview_temperature;
    private boolean m_bRunning = true;

    @Override
    public void onPause()
    {
        super.onPause();
        if (temperReadThread != null) {
            try {
                temperReadThread.interrupt();
                try {
                    if(null != temperReadThread.inputStream)
                        temperReadThread.inputStream.close();
                }
                catch (Exception ex){}
                temperReadThread.join();
                temperReadThread = null;
            } catch (InterruptedException ex) {

            }
        }
        m_bRunning = false;
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (temperReadThread == null)
        {
            temperReadThread = new TemperReadThread(this);
            temperReadThread.start();
        }
        m_bRunning = true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        textview_temperature = (TextView)findViewById(R.id.textview_temperature);
        temperReadThread = new TemperReadThread(this);
        temperReadThread.start();
    }

    private class TemperReadThread extends Thread
    {
        public InputStream inputStream = null;
        Socket socket = null;
        private MediaPlayer mpAlarmNoConnection = null, mpAlarmTooCold = null, mpAlarmTooHot = null;
        private AppCompatActivity m_act;
        TemperReadThread(AppCompatActivity act)
        {
            m_act = act;
        }

        public boolean switchOnTethering() {
            boolean bRes = false;
            Object obj = getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Method m : obj.getClass().getDeclaredMethods()) {

                if (m.getName().equals("tether")) {
                    try {
                        m.invoke(obj, "usb0");
                        bRes = true;
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    } catch (InvocationTargetException e) {
                        e.printStackTrace();
                    }
                }
            }
            return bRes;
        }

        private Boolean read_exact(byte[] buffer, int cnt) throws Exception
        {
            if(inputStream == null)
                return false;
            int iToRead = cnt;
            int offset = 0;
            int read;
            while(iToRead > 0)
            {
                read = inputStream.read(buffer, offset, iToRead);
                if(read < 1)
                    throw new Exception("read_exact");
                iToRead -= read;
                offset += read;
            }
            return true;
        }

        private int byteArrayToInt(byte[] b)
        {
            return   b[0] & 0xFF |
                    (b[1] & 0xFF) << 8 |
                    (b[2] & 0xFF) << 16 |
                    (b[3] & 0xFF) << 24;
        }

        @Override
        public void run() {

            byte[] buffer = new byte[4];

            String strHost = "192.168.42.1";
            mpAlarmNoConnection = MediaPlayer.create(m_act, R.raw.airbus_autopilot);
            mpAlarmTooCold = MediaPlayer.create(m_act, R.raw.holodno);
            mpAlarmTooHot = MediaPlayer.create(m_act, R.raw.peregrew);
            int iPort = 5001;
            InetSocketAddress socketAddress;

            setTextSize(16);

            try {
                socketAddress = new InetSocketAddress(strHost, iPort);
            }
            catch (Exception ex) {
                setMessage(ex.getMessage());
                return;
            }

            boolean bGoOn = true;
            while (bGoOn) {
                setTextSize(16);
                boolean bConnected = false;
                int attempts = 0;
                while (!bConnected)
                {
                    String strConnStatus = String.format("Connecting(%d) to %s:%d...", attempts++, strHost, iPort);
                    setMessage(strConnStatus);
                    try {
                        socket = new Socket();
                        socket.connect(socketAddress, 1000);
                        bConnected = true;
                        socket.setSoTimeout(3000);
                        inputStream = socket.getInputStream();
                    }
                    catch(IOException ex)
                    {
                        switchOnTethering();
                        if(!mpAlarmNoConnection.isPlaying())
                            mpAlarmNoConnection.start();
                        setMessage(strConnStatus + ex.getMessage());
                        try
                        {
                            sleep(1000, 0);
                        }
                        catch (InterruptedException ex_sleep){
                            bGoOn = false;
                            break;
                        }
                    }
                }

                setTextSize(72);

                int iCentralTemperature = 37000;
                int iAlarmDelta = 2000;
                boolean bColdAlarm = false;
                while(bGoOn) {
                    int iTemperature;
                    try {
                        read_exact(buffer, 4);
                        iTemperature = byteArrayToInt(buffer);
                        setMessage(String.format("%.3f°C", (float)iTemperature/1000));
                    } catch (Exception ex) {
                        try {
                            if(interrupted())
                                bGoOn = false;
                            inputStream.close();
                            inputStream = null;
                            socket.close();
                            socket = null;
                        } catch (Exception ex_io) {
                        }
                        break;
                    }
                    if(iTemperature > iCentralTemperature)
                        bColdAlarm = true;
                    if(iTemperature > iCentralTemperature + iAlarmDelta)
                    {
                        if(!mpAlarmTooHot.isPlaying())
                            mpAlarmTooHot.start();
                    }else if(iTemperature < iCentralTemperature - iAlarmDelta) {
                        if (bColdAlarm && !mpAlarmTooCold.isPlaying())
                            mpAlarmTooCold.start();
                    }
                }
            }

            try {
                if(null != inputStream) {
                    inputStream.close();
                    inputStream = null;
                }

                if(null != socket) {
                    socket.close();
                    socket = null;
                }
            }
            catch (Exception ex_io){}
        }

        private void setTextSize(final int iTextSize)
        {
            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    textview_temperature.setTextSize(TypedValue.COMPLEX_UNIT_SP, iTextSize);
                }
            });
        }
        private void setMessage(final String str)
        {
            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    textview_temperature.setText(str);
                }
            });
        }
    }
}
