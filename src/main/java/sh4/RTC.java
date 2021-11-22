package sh4;

/*
 * http://www.promethos.org/lxr/http/source/arch/sh/boards/dreamcast/rtc.c
 */
public class RTC {

	 public static final int TWENTY_YEARS = ((20 * 365 + 5) * 86400);
	 
	 /*
	  * the epoch of the aica rtc as can be seen from the above link is 1950
	  * and the initial value of the dreamcast is 1/1/98 which means 48 years after
	  * epoch.
	  * The following code calculates the seconds from that
	  */
	 public static int time = ((48 * 365 + 5) * 86400);
	 
	 public static final int read (int address)
	 {
	 	switch(address & 0xf)
	 	{
	 	case 0:		return (time >>> 16) & 0xffff;
	 	case 4:		return time & 0xffff;
	 	default:	return 0;
	 	}
	 }

	 public static void write(int address, int value)
	 {
	 	switch(address & 0xf)
	 	{
	 	case 0: 
	 		time &= 0x0000ffff;
	 		time |= value << 16;
	 		return;

	 	case 4:
	 		time &= 0xffff0000;
	 		time |= value;
	 		return;
	 	}
	 }
	
}
