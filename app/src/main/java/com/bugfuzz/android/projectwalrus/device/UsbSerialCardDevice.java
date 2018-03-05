package com.bugfuzz.android.projectwalrus.device;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.util.Pair;

import com.bugfuzz.android.projectwalrus.R;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import org.apache.commons.lang3.ArrayUtils;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public abstract class UsbSerialCardDevice<T> extends UsbCardDevice {

    private final BlockingQueue<T> receiveQueue = new LinkedBlockingQueue<>();
    private UsbSerialDevice usbSerialDevice;
    private volatile boolean receiving;
    private byte[] buffer = new byte[0];

    public UsbSerialCardDevice(Context context, UsbDevice usbDevice) throws IOException {
        super(context, usbDevice);

        usbSerialDevice = UsbSerialDevice.createUsbSerialDevice(usbDevice, usbDeviceConnection);
        if (!usbSerialDevice.open())
            throw new IOException(context.getString(R.string.failed_open_usb_serial_device));

        setupSerialParams(usbSerialDevice);

        usbSerialDevice.read(new UsbSerialInterface.UsbReadCallback() {
            @Override
            public void onReceivedData(byte[] in) {
                buffer = ArrayUtils.addAll(buffer, in);

                for (; ; ) {
                    Pair<T, Integer> sliced = sliceIncoming(buffer);
                    if (sliced == null)
                        break;

                    buffer = ArrayUtils.subarray(buffer, sliced.second, buffer.length);

                    if (receiving)
                        try {
                            receiveQueue.put(sliced.first);
                        } catch (InterruptedException ignored) {
                        }
                }
            }
        });
    }

    protected void setupSerialParams(UsbSerialDevice usbSerialDevice) {
    }

    @Override
    public void close() {
        usbSerialDevice.close();
        usbSerialDevice = null;

        super.close();
    }

    protected void setReceiving(boolean receiving) {
        if (receiving)
            receiveQueue.clear();

        this.receiving = receiving;
    }

    abstract protected Pair<T, Integer> sliceIncoming(byte[] in);

    abstract protected byte[] formatOutgoing(T out);

    protected T receive(long timeout) {
        if (!receiving)
            throw new RuntimeException("Not receiving");

        try {
            return receiveQueue.poll(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }

    protected void send(T out) {
        byte[] bytes = formatOutgoing(out);
        if (bytes == null)
            throw new RuntimeException("Failed to format outgoing");

        usbSerialDevice.write(bytes);
    }

    protected <O> O receive(ReceiveSink<T, O> receiveSink) throws IOException {
        return receive(receiveSink, 250);
    }

    protected <O> O receive(ReceiveSink<T, O> receiveSink, long internalTimeout) throws IOException {
        while (receiveSink.wantsMore()) {
            T in = receive(internalTimeout);
            if (in == null)
                continue;

            O result = receiveSink.onReceived(in);
            if (result != null)
                return result;
        }

        return null;
    }

    protected abstract static class ReceiveSink<T, O> {
        public abstract O onReceived(T in) throws IOException;

        public boolean wantsMore() {
            return true;
        }
    }

    protected abstract static class WatchdogReceiveSink<T, O> extends ReceiveSink<T, O> {

        private final long timeout;
        private long lastWatchdogReset;

        public WatchdogReceiveSink(long timeout) {
            this.timeout = timeout;

            resetWatchdog();
        }

        protected void resetWatchdog() {
            lastWatchdogReset = System.currentTimeMillis();
        }

        public boolean wantsMore() {
            return System.currentTimeMillis() < lastWatchdogReset + timeout;
        }
    }
}
