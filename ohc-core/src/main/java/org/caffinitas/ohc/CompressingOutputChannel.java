/*
 *      Copyright (C) 2014 Robert Stupp, Koeln, Germany, robert-stupp.de
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.caffinitas.ohc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

import org.xerial.snappy.Snappy;

import static org.caffinitas.ohc.Constants.*;

final class CompressingOutputChannel implements WritableByteChannel
{
    private final WritableByteChannel delegate;
    private final long bufferAddress;
    private final int uncompressedChunkSize;
    private ByteBuffer buffer;

    /**
     * @param delegate              channel to write to
     * @param uncompressedChunkSize chunk size of uncompressed that data that can be compressed in one iteration
     */
    CompressingOutputChannel(WritableByteChannel delegate, int uncompressedChunkSize) throws IOException
    {
        this.delegate = delegate;
        int maxCLen = Snappy.maxCompressedLength(uncompressedChunkSize);
        int bufferCapacity = 4 + maxCLen;
        this.bufferAddress = Uns.allocate(bufferCapacity);
        if (bufferAddress == 0L)
            throw new IOException("Unable to allocate " + bufferCapacity + " bytes in off-heap");
        this.buffer = Uns.directBufferFor(bufferAddress, 0L, bufferCapacity);
        this.uncompressedChunkSize = uncompressedChunkSize - 4;

        buffer.putInt(HEADER);
        buffer.putInt(1);
        buffer.putInt(uncompressedChunkSize);
        buffer.putInt(maxCLen);
        buffer.flip();
        delegate.write(buffer);
        buffer.clear();
    }

    public void close() throws IOException
    {
        if (buffer == null)
            return;

        this.buffer = null;
        Uns.free(bufferAddress);
    }

    public int write(ByteBuffer src) throws IOException
    {
        int sz = src.remaining();

        ByteBuffer s = src.duplicate();
        src.position(src.position() + sz);

        while (sz > 0)
        {
            int chunkSize = sz > uncompressedChunkSize ? uncompressedChunkSize : sz;

            // TODO add output buffering ?

            // write a block of compressed data prefixed by an int indicating the length of the compressed buffer
            buffer.clear();
            buffer.position(4);
            s.limit(s.position() + chunkSize);
            int cLen = Snappy.compress(s, buffer);
            buffer.putInt(0, cLen);

            buffer.position(0);
            buffer.limit(4 + cLen);
            writeFully(delegate, buffer);

            s.position(s.position() + chunkSize);
            sz -= chunkSize;
        }

        return sz;
    }

    public boolean isOpen()
    {
        return buffer != null;
    }
}