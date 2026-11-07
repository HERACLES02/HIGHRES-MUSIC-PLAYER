package com.localmusic.outputprobe;

public final class BufferChecks {
    public static void main(String[] args) {
        int assertions = 0;
        for (int frame : new int[]{4,8}) for (int minimum : new int[]{1,7,1025,16000}) {
            int small = ProbeBuffer.bytes(minimum,96000,frame,false);
            if (small < minimum || small % frame != 0 || small >= minimum + frame)
                throw new AssertionError("Small request must fit minimum and whole stereo frames");
            assertions++;
            int large = ProbeBuffer.bytes(minimum,96000,frame,true);
            if (large < 96000*frame/4 || large < small || large % frame != 0)
                throw new AssertionError("Large request must cover 250 ms without cutting minimum");
            assertions++;
        }
        for (int bad : new int[]{-2,0,1048577,Integer.MAX_VALUE}) {
            try { ProbeBuffer.bytes(bad,96000,8,false); throw new AssertionError("Bad minimum accepted"); }
            catch (IllegalArgumentException expected) { assertions++; }
        }
        System.out.println("Output-probe buffer checks passed: " + assertions);
    }
}
