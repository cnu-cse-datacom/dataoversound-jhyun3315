package com.example.sound.devicesound;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.*;

import java.util.ArrayList;
import java.util.List;

import static java.lang.Math.abs;
import static java.lang.Math.min;

public class Listentone {

    int HANDSHAKE_START_HZ = 4096;
    int HANDSHAKE_END_HZ = 5120 + 1024;

    int START_HZ = 1024;
    int STEP_HZ = 256;
    int BITS = 4;

    int FEC_BYTES = 4;

    private int mAudioSource = MediaRecorder.AudioSource.MIC;
    private int mSampleRate = 44100;
    private int mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private float interval = 0.1f;

    private int mBufferSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioFormat);

    public AudioRecord mAudioRecord = null;
    int audioEncodig;
    boolean startFlag;
    FastFourierTransformer transform;


    public Listentone() {
        transform = new FastFourierTransformer(DftNormalization.STANDARD);
        startFlag = false;
        mAudioRecord = new AudioRecord(mAudioSource, mSampleRate, mChannelCount, mAudioFormat, mBufferSize);
        mAudioRecord.startRecording();
    }


    private double findFrequency(double[] toTransform) {

        int len = toTransform.length;
        double[] real = new double[len];
        double[] img = new double[len];
        double realNum;
        double imgNum;
        double[] mag = new double[len];

        Complex[] complx = transform.transform(toTransform, TransformType.FORWARD);
        Double[] freq = this.fftfreq(complx.length, 1);

        for (int i = 0; i < complx.length; i++) {
            realNum = complx[i].getReal(); //실수부분
            imgNum = complx[i].getImaginary(); //복소수부분
            mag[i] = Math.sqrt((realNum * realNum) + (imgNum * imgNum));
        }

        double peak_coeff = 0;
        int index = 0;

        for (int i = 0; i < complx.length; i++) {
            if (peak_coeff < mag[i]) {
                peak_coeff = mag[i];
                index = i;
            }
        }
        Double peak_freq = freq[index];
        return abs(peak_freq * mSampleRate);
    }

    private Double[] fftfreq(int length, int duration) {
        double val = 1.0 / (length * duration);
        int[] results = new int[length];
        Double[] result = new Double[length];

        int N1 = (length - 1) / 2 + 1;

        for (int i = 0; i <= N1; i++) {
            results[i] = i;
        }

        int N2 = -(length / 2);
        for (int i = N1 + 1; i < length; i++) {
            results[i] = N2;
            N2++;
        }

        for (int i = 0; i < length; i++) {
            result[i] = results[i] * val;
        }
        return result;
    }

    private int findPowersize(int round) {
        int p = 1;
        while (true) {
            p *= 2;
            if (p >= round)
                return p;
        }
    }

    public boolean match(double freq1, double freq2) {
        return abs(freq1 - freq2) < 20;
    }

    public List<Integer> decode_bitchunks(int chunk_bits, List<Integer> new_bit_chunks) {
        List<Integer> out_bytes = new ArrayList<>();
        int next_read_chunk = 0;
        int next_read_bit = 0;
        int Byte = 0;
        int bits_left = 8;

        while (next_read_chunk < new_bit_chunks.size()) {
            int can_fill = chunk_bits - next_read_bit;
            int to_fill = min(bits_left, can_fill);
            int offset = chunk_bits - next_read_bit - to_fill;
            Byte <<= to_fill;
            int shifted = new_bit_chunks.get(next_read_chunk) & (((1 << to_fill) - 1) << offset);
            Byte |= shifted >> offset;
            bits_left -= to_fill;
            next_read_bit += to_fill;

            if (bits_left <= 0) {
                out_bytes.add(Byte);
                Byte = 0;
                bits_left = 8;
            }

            if (next_read_bit >= chunk_bits) {
                next_read_chunk += 1;
                next_read_bit -= chunk_bits;
            }
        }
        return out_bytes;
    }

    public List<Integer> extract_packet(List<Double> freqs) {
        List<Double> freq = new ArrayList<>();
        List<Integer> bit_chucks = new ArrayList<>();
        List<Integer> new_bit_chucks = new ArrayList<>();

        for (int i = 0; i < freqs.size(); i++) {
            freq.add(freqs.get(i));
        }

        for (int i = 0; i < freq.size(); i++) {
            int f = (int) (Math.round((freq.get(i) - START_HZ) / STEP_HZ));
            bit_chucks.add(f);
        }

        for (int i = 1; i < bit_chucks.size(); i++) {
            if (bit_chucks.get(i) > 0 && bit_chucks.get(i) < 16) {
                new_bit_chucks.add(bit_chucks.get(i));
            }
        }

        return decode_bitchunks(BITS, new_bit_chucks);
    }

    public void PreRequest() {
        int blocksize = findPowersize((int) (long) Math.round(interval / 2 * mSampleRate)); //frame수
        short[] buffer = new short[blocksize]; //소리가 들어감
        double[] chunk = new double[blocksize];

        ArrayList<Double> packet = new ArrayList<>();
        List<Integer> byte_stream = new ArrayList<>();
        StringBuffer forStr = new StringBuffer();
        boolean in_packet = false;

        while (true) {
           // int bufferedReadResult = mAudioRecord.read(buffer, 0, blocksize);

            for (int i = 0; i < buffer.length; i++) {
                chunk[i] = buffer[i];
            }
            double dom = findFrequency(chunk); //소리를 주파수로 변환

            if (in_packet && match(dom, HANDSHAKE_END_HZ)) {
                byte_stream = extract_packet(packet); //패킷에 들어있는 각 주파수의 소리들을 아스키코드로 변환해줌

                for (int i = 0; i <  byte_stream.size(); i++) {
                    forStr.append((char) (int) (byte_stream.get(i)));
                }

                Log.d("ListenTone str: ", forStr.toString());
                packet.clear();
                break;
            } else if (in_packet) {
                packet.add(dom);
                Log.d("ListenTone dom: ", Double.toString(dom));
            } else if (match(dom, HANDSHAKE_START_HZ))
                in_packet = true;
        }
    }
}


