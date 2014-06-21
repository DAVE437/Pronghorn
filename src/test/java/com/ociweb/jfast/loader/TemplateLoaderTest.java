package com.ociweb.jfast.loader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.field.TypeMask;
import com.ociweb.jfast.generator.DispatchLoader;
import com.ociweb.jfast.generator.FASTClassLoader;
import com.ociweb.jfast.primitive.PrimitiveReader;
import com.ociweb.jfast.primitive.PrimitiveWriter;
import com.ociweb.jfast.primitive.adapter.FASTInputByteArray;
import com.ociweb.jfast.primitive.adapter.FASTInputByteBuffer;
import com.ociweb.jfast.primitive.adapter.FASTOutputByteArray;
import com.ociweb.jfast.stream.DispatchObserver;
import com.ociweb.jfast.stream.FASTDecoder;
import com.ociweb.jfast.stream.FASTDynamicWriter;
import com.ociweb.jfast.stream.FASTInputReactor;
import com.ociweb.jfast.stream.FASTListener;
import com.ociweb.jfast.stream.FASTReaderInterpreterDispatch;
import com.ociweb.jfast.stream.FASTRingBuffer;
import com.ociweb.jfast.stream.FASTRingBufferReader;
import com.ociweb.jfast.stream.FASTWriterInterpreterDispatch;

public class TemplateLoaderTest {


    
    
    @Test
    public void buildRawCatalog() {

        byte[] catalogByteArray = buildRawCatalogData();
        assertEquals(709, catalogByteArray.length);
               
        
        // reconstruct Catalog object from stream
        TemplateCatalogConfig catalog = new TemplateCatalogConfig(catalogByteArray);

        boolean ok = false;
        int[] script = null;
        try {
            // /performance/example.xml contains 3 templates.
            assertEquals(3, catalog.templatesCount());

            script = catalog.fullScript();
            assertEquals(54, script.length);
            assertEquals(TypeMask.Group, TokenBuilder.extractType(script[0]));// First
                                                                                  // Token

            // CMD:Group:010000/Close:PMap::010001/9
            assertEquals(TypeMask.Group, TokenBuilder.extractType(script[script.length - 1]));// Last
                                                                                              // Token

            ok = true;
        } finally {
            if (!ok) {
                System.err.println("Script Details:");
                if (null != script) {
                    System.err.println(convertScriptToString(script));
                }
            }
        }
    }

    private String convertScriptToString(int[] script) {
        StringBuilder builder = new StringBuilder();
        for (int token : script) {

            builder.append(TokenBuilder.tokenToString(token));

            builder.append("\n");
        }
        return builder.toString();
    }

    
    
    @Test
    public void testDecodeComplex30000() {
        
      Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        
        byte[] catBytes = buildRawCatalogData();
        final TemplateCatalogConfig catalog = new TemplateCatalogConfig(catBytes); 

        // connect to file
        URL sourceData = getClass().getResource("/performance/complex30000.dat");
        File sourceDataFile = new File(sourceData.getFile().replace("%20", " "));
        long totalTestBytes = sourceDataFile.length();

        int maxPMapCountInBytes = 2 + ((Math.max(
                catalog.maxTemplatePMapSize(), catalog.maxNonTemplatePMapSize()) + 2) * catalog.getMaxGroupDepth());

      
        PrimitiveReader reader = new PrimitiveReader(buildBytesForTestingByteArray(sourceDataFile), maxPMapCountInBytes);
        
        FASTClassLoader.deleteFiles();
        
        FASTDecoder readerDispatch = DispatchLoader.loadDispatchReader(catBytes);
    //    FASTDecoder readerDispatch = new FASTReaderInterpreterDispatch(catBytes);//not using compiled code
        

        
        
        
        System.err.println("using: "+readerDispatch.getClass().getSimpleName());
        System.gc();
        
        FASTRingBuffer queue = readerDispatch.ringBuffer(0);      

        int warmup = 64;
        int count = 1024;
        int result = 0;
        final int[] fullScript = catalog.getScriptTokens();
        
        
        final byte[] preamble = new byte[catalog.clientConfig.getPreableBytes()];

        final AtomicInteger msgs = new AtomicInteger();
        int frags = 0;      
        
        final AtomicLong totalBytesOut = new AtomicLong();
        final AtomicLong totalRingInts = new AtomicLong();

        
        FASTInputReactor reactor;

        
        int iter = warmup;
        while (--iter >= 0) {
            msgs.set(0);
            frags = 0;

            reactor = new FASTInputReactor(readerDispatch,reader);
            FASTRingBuffer rb = readerDispatch.ringBuffer(0);
            rb.reset();
         //   FASTRingBuffer.dump(rb);//common starting spot??
            
            
            while (FASTInputReactor.pump(reactor)>=0) {
                FASTRingBuffer.moveNext(rb);
             //   System.err.println(templateId);
                if (rb.isNewMessage) {
                    int templateId = rb.messageId;
                    //TODO: AAA, this count is wrong it should only be 3000
                    
                    msgs.incrementAndGet();
                    

                    // this is a template message.
                    int bufferIdx = 0;
                    
                    if (preamble.length > 0) {
                        int i = 0;
                        int s = preamble.length;
                        while (i < s) {
                            FASTRingBufferReader.readInt(queue, bufferIdx);
                            i += 4;
                            bufferIdx++;
                        }
                    }

                   // int templateId2 = FASTRingBufferReader.readInt(queue, bufferIdx);
                    bufferIdx += 1;// point to first field
                    assertTrue("found " + templateId, 1 == templateId || 2 == templateId || 99 == templateId);

                    int i = catalog.getTemplateStartIdx()[templateId];
                    int limit = catalog.getTemplateLimitIdx()[templateId];
                    // System.err.println("new templateId "+templateId);
                    while (i < limit) {
                        int token = fullScript[i++];
                        // System.err.println("xxx:"+bufferIdx+" "+TokenBuilder.tokenToString(token));

                        if (isText(token)) {
                            totalBytesOut.addAndGet(4 * FASTRingBufferReader.readTextLength(queue, bufferIdx));
                        }

                        // find the next index after this token.
                        bufferIdx += TypeMask.ringBufferFieldSize[TokenBuilder.extractType(token)];

                    }
                    totalBytesOut.addAndGet(4 * bufferIdx);
                    totalRingInts.addAndGet(bufferIdx);

                    // must dump values in buffer or we will hang when reading.
                    // only dump at end of template not end of sequence.
                    // the removePosition must remain at the beginning until
                    // message is complete.
                    
                    //NOTE: MUST NOT DUMP IN THE MIDDLE OF THIS LOOP OR THE PROCESSING GETS OFF TRACK
                    //FASTRingBuffer.dump(queue);
                }
            }
            
            rb.reset();
            
            
            //fastInput.reset();
            PrimitiveReader.reset(reader);
            readerDispatch.reset(catalog.dictionaryFactory());
        }

        totalBytesOut.set(totalBytesOut.longValue()/warmup);
        totalRingInts.set(totalRingInts.longValue()/warmup);
        
        iter = count;
        while (--iter >= 0) {
            if (Thread.interrupted()) {
                System.exit(0);
            }
            
            reactor = new FASTInputReactor(readerDispatch,reader);
            
            FASTRingBuffer rb = null; 
            rb =  readerDispatch.ringBuffer(0);
            rb.reset();
            
            double start = System.nanoTime();

            while (FASTInputReactor.pump(reactor)>=0) {
                FASTRingBuffer.moveNext(rb);
            }
            
            double duration = System.nanoTime() - start;
            if ((0x7F & iter) == 0) {
                int ns = (int) duration;
                float mmsgPerSec = (msgs.intValue() * (float) 1000l / ns);
                float nsPerByte = (ns / (float) totalTestBytes);
                int mbps = (int) ((1000l * totalTestBytes * 8l) / ns);
                
                float mfieldPerSec = (totalRingInts.longValue()* (float) 1000l / ns);

                System.err.println("Duration:" + ns + "ns " + " " + mmsgPerSec + "MM/s " + " " + nsPerByte + "nspB "
                        + " " + mbps + "mbps " + " In:" + totalTestBytes + " Out:" + totalBytesOut + " cmpr:"
                        + (1f-(totalTestBytes / (float) totalBytesOut.longValue())) + " Messages:" + msgs + " Frags:" + frags
                        + " RingInts:"+totalRingInts+ " mfps "+mfieldPerSec 
                        ); // Phrases/Clauses
                // Helps let us kill off the job.
            }

            // //////
            // reset the data to run the test again.
            // //////
            //fastInput.reset();
            PrimitiveReader.reset(reader);
            readerDispatch.reset(catalog.dictionaryFactory());

        }

    }

    private boolean isText(int token) {
        return 0x08 == (0x1F & (token >>> TokenBuilder.SHIFT_TYPE));
    }

    private FASTInputByteBuffer buildInputForTestingByteBuffer(File sourceDataFile) {
        long totalTestBytes = sourceDataFile.length();
        FASTInputByteBuffer fastInput = null;
        try {
            FileChannel fc = new RandomAccessFile(sourceDataFile, "r").getChannel();
            MappedByteBuffer mem = fc.map(FileChannel.MapMode.READ_ONLY, 0, totalTestBytes);
            fastInput = new FASTInputByteBuffer(mem);
            fc.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return fastInput;
    }



    @Test
    public void testDecodeEncodeComplex30000() {
        byte[] catBytes = buildRawCatalogData();
        final TemplateCatalogConfig catalog = new TemplateCatalogConfig(catBytes);

        // connect to file
        URL sourceData = getClass().getResource("/performance/complex30000.dat");
        File sourceDataFile = new File(sourceData.getFile().replace("%20", " "));
        long totalTestBytes = sourceDataFile.length();

        FASTInputByteArray fastInput = buildInputForTestingByteArray(sourceDataFile);

        // New memory mapped solution. No need to cache because we warm up and
        // OS already has it.
        // FASTInputByteBuffer fastInput =
        // buildInputForTestingByteBuffer(sourceDataFile);

        PrimitiveReader reader = new PrimitiveReader(2048, fastInput, 32);
        
        FASTDecoder readerDispatch = DispatchLoader.loadDispatchReader(catBytes);
       // readerDispatch = new FASTReaderInterpreterDispatch(catBytes);//not using compiled code
        
        final AtomicInteger msgs = new AtomicInteger();
        
        FASTListener listener = new FASTListener() {
            
            @Override
            public void fragment(int templateId, FASTRingBuffer buffer) {
                msgs.incrementAndGet();
            }
            
            @Override
            public void fragment() {
                // TODO Auto-generated method stub
                
            }
            
        };
        
        FASTInputReactor reactor = new FASTInputReactor(readerDispatch,reader);
        
        FASTRingBuffer queue = readerDispatch.ringBuffer(0);

        byte[] targetBuffer = new byte[(int) (totalTestBytes)];
        FASTOutputByteArray fastOutput = new FASTOutputByteArray(targetBuffer);

        // TODO: Z, force this error and add friendly message, when minimize
        // latency set to false these need to be much bigger?
        int writeBuffer = 2048;
        
        int maxGroupCount = catalog.getScriptTokens().length; //overkill but its fine for testing. 
        // NOTE: may need to be VERY large if minimize
        // latency is turned off!!
        
        PrimitiveWriter writer = new PrimitiveWriter(writeBuffer, fastOutput, maxGroupCount, false);
        FASTWriterInterpreterDispatch writerDispatch = new FASTWriterInterpreterDispatch(catalog,queue);

        FASTDynamicWriter dynamicWriter = new FASTDynamicWriter(writer, catalog, queue, writerDispatch);

        final Map<Long, String> reads = new HashMap<Long, String>();
        readerDispatch.setDispatchObserver(new DispatchObserver() {

            @Override
            public void tokenItem(long absPos, int token, int cursor, String value) {
                String msg = "\n    R_" + TokenBuilder.tokenToString(token) + " id:"
                        + (cursor >= catalog.scriptFieldIds.length ? "ERR" : "" + catalog.scriptFieldIds[cursor])
                        + " curs:" + cursor + " tok:" + token + " " + value;
                if (reads.containsKey(absPos)) {
                    msg = reads.get(absPos) + " " + msg;
                }
                reads.put(absPos, msg);
            }
        });

        System.gc();
        
        int warmup = 20;// set much larger for profiler
        int count = 128;

        long wroteSize = 0;
        msgs.set(0);
        int grps = 0;
        int iter = warmup;
        while (--iter >= 0) {
            msgs.set(0);
            grps = 0;
            
      //idea      
//            int bid; 
//            while ((bid = reactor.pump())>=0) {
//                FASTRingBuffer rb =  readerDispatch.ringBuffer(bid);
//                rb.moveNext();
//                int templateId = rb.messageId();
//                if (templateId!=-1) {
//                    FASTRingBufferReader.dump(queue);
//                }
//            }
            writerDispatch.reset();
            while (FASTInputReactor.pump(reactor)>=0) {
                
                //TODO: AA, confirm that nextMessage blocks and does not continue when there is no data
                //TODO: AA, confirm that moveNext write pattern is continued to be called when we have data.
                
//                while (queue.contentRemaining()>0) {
                //    System.err.println("remain "+queue.contentRemaining());
                    FASTRingBuffer.moveNext(queue);
                    if (queue.messageId>=0) {
                        
                        //TODO: must confirm each write as it happens in order to find bugs when they happen.
                        
                        dynamicWriter.write();
                       grps++;
                       //System.err.println("grps "+grps);
                   }
  //              }
            }
            
    //old delete        
//            int flags = 0; // same id needed for writer construction
//            while (0 != (flags = reactor.select())) {
//                
//                while (queue.hasContent()) {
//                    dynamicWriter.write();
//                }
//                grps++;
//                
//            }

            queue.reset();

            fastInput.reset();
            PrimitiveReader.reset(reader);
            readerDispatch.reset(catalog.dictionaryFactory());

            PrimitiveWriter.flush(writer);
            wroteSize = Math.max(wroteSize, PrimitiveWriter.totalWritten(writer));
            fastOutput.reset();
            PrimitiveWriter.reset(writer);
            dynamicWriter.reset(true);

            // only need to collect data on the first run
            readerDispatch.setDispatchObserver(null);
            writerDispatch.setDispatchObserver(null);
        }

        scanForMismatch(targetBuffer, fastInput.getSource(), reads);
        // Expected total read fields:2126101
        assertEquals("test file bytes", totalTestBytes, wroteSize);

        iter = count;
        while (--iter >= 0) {

            double start = System.nanoTime();
            
            while (FASTInputReactor.pump(reactor)>=0) {
                while (FASTRingBuffer.contentRemaining(queue)>0) {
                    dynamicWriter.write();
                }
            }
            
            double duration = System.nanoTime() - start;

            if ((0x3F & iter) == 0) {
                int ns = (int) duration;
                float mmsgPerSec = (msgs.intValue() * (float) 1000l / ns);
                float nsPerByte = (ns / (float) totalTestBytes);
                int mbps = (int) ((1000l * totalTestBytes * 8l) / ns);

                System.err.println("Duration:" + ns + "ns " + " " + mmsgPerSec + "MM/s " + " " + nsPerByte + "nspB "
                        + " " + mbps + "mbps " + " Bytes:" + totalTestBytes + " Messages:" + msgs + " Groups:" + grps); // Phrases/Clauses
            }

            // //////
            // reset the data to run the test again.
            // //////
            queue.reset();

            fastInput.reset();
            PrimitiveReader.reset(reader);
            readerDispatch.reset(catalog.dictionaryFactory());

            fastOutput.reset();
            PrimitiveWriter.reset(writer);
            dynamicWriter.reset(true);

        }

    }

    private void scanForMismatch(byte[] targetBuffer, byte[] sourceBuffer, Map<Long, String> reads) {
        int lookAhead = 11;
        int maxDisplay = 31;
        int nthErr = 1;

        int i = 0;
        int err = 0;
        int displayed = 0;
        while (i < sourceBuffer.length && displayed < maxDisplay) {
            // Check data for mismatch
            if (i + lookAhead < sourceBuffer.length && i + lookAhead < targetBuffer.length
                    && sourceBuffer[i + lookAhead] != targetBuffer[i + lookAhead]) {
                err++;
                ;
            }

            if (err >= nthErr) {
                displayed++;
                StringBuilder builder = new StringBuilder();
                builder.append(i).append(' ').append(" R").append(hex(sourceBuffer[i])).append(' ').append(" W")
                        .append(hex(targetBuffer[i])).append(' ');

                builder.append(" R").append(bin(sourceBuffer[i])).append(' ');
                builder.append(" W").append(bin(targetBuffer[i])).append(' ');

                if (sourceBuffer[i] != targetBuffer[i]) {
                    builder.append(" ***ERROR***  decimals " + (0x7F & sourceBuffer[i]) + "  "
                            + (0x7F & targetBuffer[i]) + " ascii "
                            + Character.toString((char) (0x7F & sourceBuffer[i])) + "  "
                            + Character.toString((char) (0x7F & targetBuffer[i])));
                }

                Long lng = Long.valueOf(i);
                if (reads.containsKey(lng)) {
                    builder.append(reads.get(lng)).append(' ');
                } else {
                    builder.append("                ");
                }

                System.err.println(builder);

            }
            i++;
        }
    }

    private String hex(int x) {
        String t = Integer.toHexString(0xFF & x);
        if (t.length() == 1) {
            return '0' + t;
        } else {
            return t;
        }
    }

    private String bin(int x) {
        String t = Integer.toBinaryString(0xFF & x);
        while (t.length() < 8) {
            t = '0' + t;
        }

        return t.substring(t.length() - 8);

    }

    static FASTInputByteArray buildInputForTestingByteArray(File fileSource) {
        byte[] fileData = null;
        try {
            // do not want to time file access so copy file to memory
            fileData = new byte[(int) fileSource.length()];
            FileInputStream inputStream = new FileInputStream(fileSource);
            int readBytes = inputStream.read(fileData);
            inputStream.close();
            assertEquals(fileData.length, readBytes);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        FASTInputByteArray fastInput = new FASTInputByteArray(fileData);
        return fastInput;
    }
    
    static byte[] buildBytesForTestingByteArray(File fileSource) {
        byte[] fileData = null;
        try {
            // do not want to time file access so copy file to memory
            fileData = new byte[(int) fileSource.length()];
            FileInputStream inputStream = new FileInputStream(fileSource);
            int readBytes = inputStream.read(fileData);
            inputStream.close();
            assertEquals(fileData.length, readBytes);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return fileData;
    }

//    private String hexString(byte[] targetBuffer) {
//        StringBuilder builder = new StringBuilder();
//
//        for (byte b : targetBuffer) {
//
//            String tmp = Integer.toHexString(0xFF & b);
//            builder.append(tmp.substring(Math.max(0, tmp.length() - 2))).append(" ");
//
//        }
//        return builder.toString();
//    }

//    private String binString(byte[] targetBuffer) {
//        StringBuilder builder = new StringBuilder();
//
//        for (byte b : targetBuffer) {
//
//            String tmp = Integer.toBinaryString(0xFF & b);
//            builder.append(tmp.substring(Math.max(0, tmp.length() - 8))).append(" ");
//
//        }
//        return builder.toString();
//    }

    public static byte[] buildRawCatalogData() {
        //this example uses the preamble feature
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setPreableBytes((short)4);

        ByteArrayOutputStream catalogBuffer = new ByteArrayOutputStream(4096);
        try {
            TemplateLoader.buildCatalog(catalogBuffer, "/performance/example.xml", clientConfig);
        } catch (Exception e) {
            e.printStackTrace();
        }

        assertTrue("Catalog must be built.", catalogBuffer.size() > 0);

        byte[] catalogByteArray = catalogBuffer.toByteArray();
        return catalogByteArray;
    }


}
