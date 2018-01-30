/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.pocketworkstation.pckeyboard;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.util.Arrays;

/**
 * Implements a static, compacted, binary dictionary of standard words.
 */
public class BinaryDictionary extends Dictionary {

    /**
     * There is difference between what java and native code can handle.
     * This value should only be used in BinaryDictionary.java
     * It is necessary to keep it at this value because some languages e.g. German have
     * really long words.
     */
    protected static final int MAX_WORD_LENGTH = 48;

    private static final String TAG = "BinaryDictionary";
    private static final int MAX_ALTERNATIVES = 16;
    private static final int MAX_WORDS = 18;
    private static final int MAX_BIGRAMS = 60;

    private static final int TYPED_LETTER_MULTIPLIER = 2;
    private static final boolean ENABLE_MISSED_CHARACTERS = true;

    private int mDicTypeId;
    private long mNativeDict;
    private int mDictLength;
    private int[] mInputCodes = new int[MAX_WORD_LENGTH * MAX_ALTERNATIVES];
    private char[] mOutputChars = new char[MAX_WORD_LENGTH * MAX_WORDS];
    private char[] mOutputChars_bigrams = new char[MAX_WORD_LENGTH * MAX_BIGRAMS];
    private int[] mFrequencies = new int[MAX_WORDS];
    private int[] mFrequencies_bigrams = new int[MAX_BIGRAMS];
    // Keep a reference to the native dict direct buffer in Java to avoid
    // unexpected deallocation of the direct buffer.
    private ByteBuffer mNativeDictDirectBuffer;

    static {
        try {
            System.loadLibrary("jni_pckeyboard");
            Log.i("PCKeyboard", "loaded jni_pckeyboard");
        } catch (UnsatisfiedLinkError ule) {
            Log.e("BinaryDictionary", "Could not load native library jni_pckeyboard");
        }
    }

    /**
     * Create a dictionary from a raw resource file
     * @param context application context for reading resources
     * @param resId the resource containing the raw binary dictionary
     */
    public BinaryDictionary(Context context, int[] resId, int dicTypeId) {
        if (resId != null && resId.length > 0 && resId[0] != 0) {
            loadDictionary(context, resId);
        }
        mDicTypeId = dicTypeId;
    }

    /**
     * Create a dictionary from input streams
     * @param context application context for reading resources
     * @param streams the resource streams containing the raw binary dictionary
     */
    public BinaryDictionary(Context context, InputStream[] streams, int dicTypeId) {
        if (streams != null && streams.length > 0) {
            loadDictionary(streams);
        }
        mDicTypeId = dicTypeId;
    }

    /**
     * Create a dictionary from a byte buffer. This is used for testing.
     * @param context application context for reading resources
     * @param byteBuffer a ByteBuffer containing the binary dictionary
     */
    public BinaryDictionary(Context context, ByteBuffer byteBuffer, int dicTypeId) {
        if (byteBuffer != null) {
            if (byteBuffer.isDirect()) {
                mNativeDictDirectBuffer = byteBuffer;
            } else {
                mNativeDictDirectBuffer = ByteBuffer.allocateDirect(byteBuffer.capacity());
                byteBuffer.rewind();
                mNativeDictDirectBuffer.put(byteBuffer);
            }
            mDictLength = byteBuffer.capacity();
        }
        mDicTypeId = dicTypeId;
    }

    private final void loadDictionary(InputStream[] is) {
        try {
            // merging separated dictionary into one if dictionary is separated
            int total = 0;

            for (int i = 0; i < is.length; i++) {
                total += is[i].available();
            }

            mNativeDictDirectBuffer =
                ByteBuffer.allocateDirect(total).order(ByteOrder.nativeOrder());
            int got = 0;
            for (int i = 0; i < is.length; i++) {
                got += Channels.newChannel(is[i]).read(mNativeDictDirectBuffer);
            }
            if (got != total) {
                Log.e(TAG, "Read " + got + " bytes, expected " + total);
            } else {

            }
            if (mDictLength > 10000) Log.i("PCKeyboard", "Loaded dictionary, len=" + mDictLength);
        } catch (IOException e) {
            Log.w(TAG, "No available memory for binary dictionary");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load native dictionary", e);
        } finally {
            try {
                if (is != null) {
                    for (int i = 0; i < is.length; i++) {
                        is[i].close();
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to close input stream");
            }
        }
    }
    
    private final void loadDictionary(Context context, int[] resId) {
        InputStream[] is = null;
        is = new InputStream[resId.length];
        for (int i = 0; i < resId.length; i++) {
            is[i] = context.getResources().openRawResource(resId[i]);
        }
        loadDictionary(is);
    }


    @Override
    public void getBigrams(final WordComposer codes, final CharSequence previousWord,
            final WordCallback callback, int[] nextLettersFrequencies) {

        char[] chars = previousWord.toString().toCharArray();
        Arrays.fill(mOutputChars_bigrams, (char) 0);
        Arrays.fill(mFrequencies_bigrams, 0);

        int codesSize = codes.size();
        Arrays.fill(mInputCodes, -1);
        int[] alternatives = codes.getCodesAt(0);
        System.arraycopy(alternatives, 0, mInputCodes, 0,
                Math.min(alternatives.length, MAX_ALTERNATIVES));


    }

    @Override
    public void getWords(final WordComposer codes, final WordCallback callback,
            int[] nextLettersFrequencies) {
        final int codesSize = codes.size();
        // Won't deal with really long words.
        if (codesSize > MAX_WORD_LENGTH - 1) return;

        Arrays.fill(mInputCodes, -1);
        for (int i = 0; i < codesSize; i++) {
            int[] alternatives = codes.getCodesAt(i);
            System.arraycopy(alternatives, 0, mInputCodes, i * MAX_ALTERNATIVES,
                    Math.min(alternatives.length, MAX_ALTERNATIVES));
        }
        Arrays.fill(mOutputChars, (char) 0);
        Arrays.fill(mFrequencies, 0);

        if (mNativeDict == 0)
            return;

        // If there aren't sufficient suggestions, search for words by allowing wild cards at
        // the different character positions. This feature is not ready for prime-time as we need
        // to figure out the best ranking for such words compared to proximity corrections and
        // completions.

    }

    @Override
    public boolean isValidWord(CharSequence word) {
        if (word == null || mNativeDict == 0) return false;
        char[] chars = word.toString().toCharArray();
        return false;
    }

    public int getSize() {
        return mDictLength; // This value is initialized on the call to openNative()
    }

    @Override
    public synchronized void close() {
        if (mNativeDict != 0) {
            mNativeDict = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }
}
