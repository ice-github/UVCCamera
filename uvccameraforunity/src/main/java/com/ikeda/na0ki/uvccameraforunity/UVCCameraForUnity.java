package com.ikeda.na0ki.uvccameraforunity;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import com.serenegiant.usb.DeviceFilter;
import com.serenegiant.usb.IButtonCallback;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.IStatusCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.nio.ByteBuffer;
import java.util.List;

public class UVCCameraForUnity {

    private final Object mSync = new Object();
    // for accessing USB and USB camera
    private USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private ImageView mImageView;//テスト用

    private Thread mConnectedLoop;
    private List<UsbDevice> mDeviceList;

    private int mFrameWidth;
    private int mFrameHeight;
    private byte[] mFrameBuffer;

    //初期化
    public void Initialize(Context context, int width, int height)
    {
        mUSBMonitor = new USBMonitor(context, mOnDeviceConnectListener);

        //フレームバッファの用意
        mFrameWidth = width;
        mFrameHeight = height;
        mFrameBuffer = new byte[width * height * 2];//RGB565
    }

    //終了
    public void Finalize()
    {
        releaseCamera();

        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
    }

    //開始
    public void Start()
    {
        mUSBMonitor.register();
        synchronized (mSync) {
            if (mUVCCamera != null) {
                mUVCCamera.startPreview();
            }
        }
    }

    //停止
    public void Stop()
    {
        synchronized (mSync) {
            if (mUVCCamera != null) {
                mUVCCamera.stopPreview();
            }
            if (mUSBMonitor != null) {
                mUSBMonitor.unregister();
            }
        }
    }

    //ImageViewの設定(テスト用)
    public void SetImageView(ImageView imageView)
    {
        mImageView = imageView;
    }

    public byte[] GetFrameBuffer()
    {
        return mFrameBuffer;
    }

    //デバイス確認
    public void Enumlate(Context context)
    {
        final List<DeviceFilter> filter = DeviceFilter.getDeviceFilters(context, com.serenegiant.uvccamera.R.xml.device_filter);
        mDeviceList = mUSBMonitor.getDeviceList(filter.get(0));

        for(UsbDevice d : mDeviceList)
        {
            Log.d("UVCCamera", "devide: " + d.toString());
        }
    }

    //接続
    public void Connect()
    {
        if(mDeviceList != null && mDeviceList.size() > 0)
        {
            //パーミッション
            mUSBMonitor.requestPermission((mDeviceList.get(0)));
        }
    }


    //-----------------------------------------------------//

    private synchronized void releaseCamera() {
        synchronized (mSync) {
            if (mUVCCamera != null) {
                try {
                    mUVCCamera.setStatusCallback(null);
                    mUVCCamera.setButtonCallback(null);
                    mUVCCamera.close();
                    mUVCCamera.destroy();
                } catch (final Exception e) {
                    //
                }
                mUVCCamera = null;
            }
        }
    }

    private UVCCamera ConnectCamera(USBMonitor.UsbControlBlock ctrlBlock, int width, int height, int mode)
    {
        UVCCamera camera = new UVCCamera();

        camera.open(ctrlBlock);
        //ステータスコールバック
        camera.setStatusCallback(new IStatusCallback() {
            @Override
            public void onStatus(final int statusClass, final int event, final int selector, final int statusAttribute, final ByteBuffer data) {
                //ステータス更新
                Log.d("UVCCamera", "onStatus");
            }
        });

        //内部で生成されるボタン用
        camera.setButtonCallback(new IButtonCallback() {
            @Override
            public void onButton(final int button, final int state) {
                Log.d("UVCCamera", "onButton");
            }
        });

        //UVCカメラとして設定
        try {
            camera.setPreviewSize(width, height, mode);
        } catch (final IllegalArgumentException e) {
            // fallback to YUV mode
            try {
                camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE);
            } catch (final IllegalArgumentException e1) {
                camera.destroy();
                return null;
            }
        }

        //フレームが来た時のコールバックを設定
        camera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_RGB565/*UVCCamera.PIXEL_FORMAT_NV21*/);


        Log.d("UVCCamera", "ConnectCamera");

        return camera;
    }


    private final USBMonitor.OnDeviceConnectListener mOnDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            //カメラがアタッチされた
            Log.d("UVCCamera", "onAttach");
        }

        @Override
        public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            Log.d("UVCCamera", "onConnect");

            releaseCamera();
            mConnectedLoop = new Thread(new Runnable() {
                @Override
                public void run() {
                    //カメラの取得
                    final UVCCamera camera = ConnectCamera(ctrlBlock, mFrameWidth, mFrameHeight, UVCCamera.FRAME_FORMAT_MJPEG);
                    if(camera == null) return;

                    //内部エラー回避用
                    Surface previewSurface = new Surface(new SurfaceTexture(0, false));
                    camera.setPreviewDisplay(previewSurface);

                    //プレビュー開始
                    camera.startPreview();

                    //カメラインスタンスの設定
                    synchronized (mSync) {
                        mUVCCamera = camera;
                    }
                }
            });

            //スレッド開始
            mConnectedLoop.start();
        }

        @Override
        public void onDisconnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock) {
            // XXX you should check whether the coming device equal to camera device that currently using
            releaseCamera();
        }

        @Override
        public void onDettach(final UsbDevice device) {
            //USB抜かれたとき
            Log.d("UVCCamera", "onDettach");
        }

        @Override
        public void onCancel(final UsbDevice device) {
        }
    };

    final Bitmap bitmap = Bitmap.createBitmap(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, Bitmap.Config.RGB_565);
    private final IFrameCallback mIFrameCallback = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame) {
            frame.clear();//何故？

            //デバッグ用imageViewが設定されていれば設定
            if (mImageView != null) {
                synchronized (bitmap) {
                    bitmap.copyPixelsFromBuffer(frame);
                }
                mImageView.post(mUpdateImageTask);
            }

            //フレームバッファへコピー
            frame.get(mFrameBuffer, 0, mFrameBuffer.length);
            //Log.d("UVCCamera", "onFrame size: " + frame.capacity());
        }
    };

    private final Runnable mUpdateImageTask = new Runnable() {
        @Override
        public void run() {
            synchronized (bitmap) {
                mImageView.setImageBitmap(bitmap);
            }
        }
    };
}
