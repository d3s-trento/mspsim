/**
 * Copyright (c) 2007, Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 * This file is part of MSPSim.
 *
 * $Id$
 *
 * -----------------------------------------------------------------
 *
 * USART
 *
 * Author  : Joakim Eriksson
 * Created : Sun Oct 21 22:00:00 2007
 * Updated : $Date$
 *           $Revision$
 */

package se.sics.mspsim.core;

public class USART extends IOUnit implements SFRModule {

  public static final boolean DEBUG = false; //true;

  // USART 0/1 register offset (0x70 / 0x78)
  public static final int UCTL = 0;
  public static final int UTCTL = 1;
  public static final int URCTL = 2;
  public static final int UMCTL = 3;
  public static final int UBR0 = 4;
  public static final int UBR1 = 5;
  public static final int URXBUF = 6;
  public static final int UTXBUF = 7;

  public static final int UTXIFG0 = 0x80;
  public static final int URXIFG0 = 0x40;

  public static final int UTXIFG1 = 0x20;
  public static final int URXIFG1 = 0x10;

  // USART SRF mod enable registers (absolute + 1)
  public static final int ME1 = 4;
  public static final int IE1 = 0;
  public static final int IFG1 = 2;

  private int uartID = 0;

  public static final int USART0_RX_VEC = 9;
  public static final int USART0_TX_VEC = 8;
  public static final int USART0_RX_BIT = 6;
  public static final int USART0_TX_BIT = 7;
  
  public static final int USART1_RX_VEC = 3;
  public static final int USART1_TX_VEC = 2;
  public static final int USART1_RX_BIT = 4;
  public static final int USART1_TX_BIT = 5;

  // Flags.
  public static final int UTCTL_TXEMPTY = 0x01;
  

  private USARTListener listener;

  private int utxifg;
  private int urxifg;
  private int rxVector;

  private int clockSource = 0;
  private int baudRate = 0;
  private int tickPerByte = 1000;
  private long nextTXReady = -1;
  private int nextTXByte = -1;
  private int txShiftReg = -1;
  private boolean transmitting = false;
  
  private MSP430Core cpu;
  private SFR sfr;

  private int uctl;
  private int utctl;
  private int urctl;
  private int umctl;
  private int ubr0;
  private int ubr1;
  private int urxbuf;
  private int utxbuf;
  private int txbit;
  
  private boolean txEnabled = false;
  private boolean rxEnabled = false;
  private boolean spiMode = false;
  
  private TimeEvent txTrigger = new TimeEvent(0) {
    public void execute(long t) {
        // Ready to transmit new byte!
        handleTransmit(t);
    }
  };
  
  /**
   * Creates a new <code>USART</code> instance.
   *
   */
  public USART(MSP430Core cpu, int[] memory, int offset) {
    super(memory, offset);
    this.cpu = cpu;
    sfr = cpu.getSFR();
    if (offset == 0x78) {
      uartID = 1;
    }

    // Initialize - transmit = ok...
    // and set which interrupts are used
    if (uartID == 0) {
      sfr.registerSFDModule(0, USART0_RX_BIT, this, USART0_RX_VEC);
      sfr.registerSFDModule(0, USART0_TX_BIT, this, USART0_TX_VEC);
      utxifg = UTXIFG0;
      urxifg = URXIFG0;
      txbit = USART0_TX_BIT;
      rxVector = USART0_RX_VEC;
    } else {
      sfr.registerSFDModule(1, USART1_RX_BIT, this, USART1_RX_VEC);
      sfr.registerSFDModule(1, USART1_TX_BIT, this, USART1_TX_VEC);
      utxifg = UTXIFG1;
      urxifg = URXIFG1;
      txbit = USART1_TX_BIT;
      rxVector = USART1_RX_VEC;
    }
    
    reset(0);
  }

  public void reset(int type) {
    nextTXReady = cpu.cycles + 100;
    txShiftReg = nextTXByte = -1;
    transmitting = false;
    clrBitIFG(utxifg | urxifg);
    utctl |= UTCTL_TXEMPTY;
    cpu.scheduleCycleEvent(txTrigger, nextTXReady);
    txEnabled = false;
    rxEnabled = false;
  }

  public void enableChanged(int reg, int bit, boolean enabled) {
    if (DEBUG) System.out.println("enableChanged: " + reg + " bit: " + bit +
        " enabled = " + enabled + " txBit: " + txbit);
    if (bit == txbit) {
      txEnabled = enabled;
    } else {
      rxEnabled = enabled;
    }
  }
  
  private void setBitIFG(int bits) {
    sfr.setBitIFG(uartID, bits);
  }

  private void clrBitIFG(int bits) {
    sfr.clrBitIFG(uartID, bits);
  }

  private int getIFG() {
    return sfr.getIFG(uartID);
  }

  private boolean isIEBitsSet(int bits) {
    return sfr.isIEBitsSet(uartID, bits);
  }

  public void setUSARTListener(USARTListener listener) {
    this.listener = listener;
  }

  // Only 8 bits / read!
  public void write(int address, int data, boolean word, long cycles) {
    address = address - offset;

    // Indicate ready to write!!! - this should not be done here...
//     if (uartID == 0) memory[IFG1] |= 0x82;
//     else memory[IFG1 + 1] |= 0x20;

//     System.out.println(">>>> Write to " + getName() + " at " +
// 		       address + " = " + data);
    switch (address) {
    case UCTL:
      uctl = data;
      spiMode = (data & 0x04) > 0;
      if (DEBUG) System.out.println(getName() + " write to UCTL " + data);
      break;
    case UTCTL:
      utctl = data;
      if (DEBUG) System.out.println(getName() + " write to UTCTL " + data);

      if (((data >> 4) & 3) == 1) {
        clockSource = MSP430Constants.CLK_ACLK;
        if (DEBUG) {
          System.out.println(getName() + " Selected ACLK as source");
        }
      } else {
        clockSource = MSP430Constants.CLK_SMCLK;
        if (DEBUG) {
          System.out.println(getName() + " Selected SMCLK as source");
        }
      }
      updateBaudRate();
      break;
    case URCTL:
      urctl = data;
      break;
    case UMCTL:
      umctl = data;
      if (DEBUG) System.out.println(getName() + " write to UMCTL " + data);
      break;
    case UBR0:
      ubr0 = data;
      updateBaudRate();
      break;
    case UBR1:
      ubr1 = data;
      updateBaudRate();
      break;
    case UTXBUF:
      if (DEBUG) System.out.print(getName() + ": USART_UTXBUF:" + (char) data + " = " + data + "\n");
      if (txEnabled || (spiMode && rxEnabled)) {
        // Interruptflag not set!
        clrBitIFG(utxifg);
        /* the TX is no longer empty ! */
        utctl &= ~UTCTL_TXEMPTY;
        /* should the interrupt be flagged off here ? - or only the flags */
        if (DEBUG) System.out.println(getName() + " flagging off transmit interrupt");
        //      cpu.flagInterrupt(transmitInterrupt, this, false);

        // Schedule on cycles here
        // TODO: adding 3 extra cycles here seems to give
        // slightly better timing in some test...

        nextTXByte = data;
        if (!transmitting) {
            /* how long time will the copy from the TX_BUF to the shift reg take? */
            /* assume 3 cycles? */
            nextTXReady = cycles + 3; //tickPerByte + 3;
            cpu.scheduleCycleEvent(txTrigger, nextTXReady);
        }
      } else {
        System.out.println("Ignoring UTXBUF data since TX not active...");
      }
      utxbuf = data;
      break;
    }
  }

  public int read(int address, boolean word, long cycles) {
    address = address - offset;
//     System.out.println(">>>>> Read from " + getName() + " at " +
// 		       address + " = " + memory[address]);
    
    switch (address) {
    case UCTL:
      if (DEBUG) System.out.println(getName() + " read from UCTL");
      return uctl;
    case UTCTL:
      if (DEBUG) System.out.println(getName() + " read from UTCTL: " + utctl);
      return utctl;
    case URCTL:
      return urctl;
    case UMCTL:
      return umctl;
    case UBR0:
      return ubr0;
    case UBR1:
      return ubr1;
    case UTXBUF:
      return utxbuf;
    case URXBUF:
      int tmp = urxbuf;
      // When byte is read - the interruptflag is cleared!
      // and error status should also be cleared later...
      if (MSP430Constants.DEBUGGING_LEVEL > 0) {
        System.out.println(getName() + " clearing rx interrupt flag " + cpu.getPC() + " byte: " + tmp);
      }
      clrBitIFG(urxifg);
      if (listener != null) {
        listener.stateChanged(USARTListener.RXFLAG_CLEARED);
      }
      return tmp;
    }
    return 0;
  }

  private void updateBaudRate() {
    int div = ubr0 + (ubr1 << 8);
    if (div == 0) {
      div = 1;
    }
    if (clockSource == MSP430Constants.CLK_ACLK) {
      if (DEBUG) {
        System.out.println(getName() + " Baud rate is (bps): " + cpu.aclkFrq / div +
            " div = " + div);
      }
      baudRate = cpu.aclkFrq / div;
    } else {
      if (DEBUG) {     
        System.out.println(getName() + " Baud rate is (bps): " + cpu.smclkFrq / div +
            " div = " + div);
      }
      baudRate = cpu.smclkFrq / div;
    }
    if (baudRate == 0) baudRate = 1;
    // Is this correct??? Is it the DCO or smclkFRQ we should have here???
    tickPerByte = (8 * cpu.smclkFrq) / baudRate;
    if (DEBUG) {
      System.out.println(getName() +  " Ticks per byte: " + tickPerByte);
    }
  }


  public String getName() {
    return "USART " + uartID;
  }

  // We should add "Interrupt serviced..." to indicate that its latest
  // Interrupt was serviced...
  public void interruptServiced(int vector) {
    /* NOTE: this is handled by SFR : clear IFG bit if interrupt is serviced */
//    System.out.println("SFR irq");
  }

  private void handleTransmit(long cycles) {
    if (cpu.getMode() >= MSP430Core.MODE_LPM3) {
      System.out.println(getName() + " Warning: USART transmission during LPM!!! " + nextTXByte);
    }

    if (transmitting) {
        /* in this case we have shifted out the last character */
        if (listener != null && txShiftReg != -1) {
            listener.dataReceived(this, txShiftReg);
        }
        /* nothing more to transmit after this - stop transmission */
        if (nextTXByte == -1) {
            utctl |= UTCTL_TXEMPTY;
            transmitting = false;
            txShiftReg = -1;
        }
    }
    /* any more chars to transmit? */
    if (nextTXByte != -1) {
        txShiftReg = nextTXByte;
        nextTXByte = -1;
        transmitting = true;
        nextTXReady = cycles + tickPerByte + 1;
        cpu.scheduleCycleEvent(txTrigger, nextTXReady);
    }
    /* txbuf always empty after this?! */
    setBitIFG(utxifg);

    if (DEBUG) {
      if (isIEBitsSet(utxifg)) {
        System.out.println(getName() + " flagging on transmit interrupt");
      }
      System.out.println(getName() + " Ready to transmit next at: " + cycles);
    }
  }


  public boolean isReceiveFlagCleared() {
    return (getIFG() & urxifg) == 0;
  }

  // A byte have been received!
  // This needs to be complemented with a method for checking if the USART
  // is ready for next byte (readyForReceive) that respects the current speed
  public void byteReceived(int b) {
    if (!rxEnabled) return;
    
    if (MSP430Constants.DEBUGGING_LEVEL > 0) {
      System.out.println(getName() + " byteReceived: " + b);
    }
    urxbuf = b & 0xff;
    // Indicate interrupt also!
    setBitIFG(urxifg);

    // Check if the IE flag is enabled! - same as the IFlag to indicate!
    if (isIEBitsSet(urxifg)) {
      if (MSP430Constants.DEBUGGING_LEVEL > 0) {
        System.out.println(getName() + " flagging receive interrupt ");
      }
    }
  }
}