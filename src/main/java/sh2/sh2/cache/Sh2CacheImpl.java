package sh2.sh2.cache;

/*
Copyright 2005 Guillaume Duhamel
Copyright 2016 Shinya Miyamoto

This file is part of Yabause.

Yabause is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

Yabause is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Yabause; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
*/

/*! \file sh2cache.c
\brief SH2 internal cache operations FIL0016332.PDF section 8
*/

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.IMemory;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil.CpuDeviceAccess;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.readBuffer;
import static sh2.S32xUtil.writeBuffer;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2CacheImpl implements Sh2Cache {

    private static final Logger LOG = LogManager.getLogger(Sh2CacheImpl.class.getSimpleName());
    private static final boolean verbose = false;

    protected Sh2CacheEntry ca;
    private CpuDeviceAccess cpu;
    private IMemory memory;
    private CacheContext ctx;
    private ByteBuffer data_array = ByteBuffer.allocate(DATA_ARRAY_SIZE); // cache (can be used as RAM)

    public Sh2CacheImpl(CpuDeviceAccess cpu, IMemory memory) {
        this.memory = memory;
        this.cpu = cpu;
        this.ca = new Sh2CacheEntry();
        this.ctx = new CacheContext();
        for (int i = 0; i < ca.way.length; i++) {
            for (int j = 0; j < ca.way[i].length; j++) {
                ca.way[i][j] = new Sh2CacheLine();
            }
        }
    }

    @Override
    public void cacheClear(Sh2CacheEntry ca) {
        for (int entry = 0; entry < CACHE_LINES; entry++) {
            ca.lru[entry] = 0;
            for (int way = 0; way < CACHE_WAYS; way++) {
                final Sh2CacheLine line = ca.way[way][entry];
                line.v = 0;
                line.tag = 0;
                Arrays.fill(line.data, 0);
            }
        }
        if (verbose) LOG.info("{} Cache clear", cpu);
        //TODO check, Chaotix
//        if(ca.enable == 0) {
//            Arrays.fill(data_array.array(), 0, data_array.capacity() >> ctx.twoWay, (byte) 0);
//        }
        return;
    }

    @Override
    public int readDirect(int addr, Size size) {
        switch (addr & AREA_MASK) {
            case CACHE_USE:
                if (ca.enable > 0) {
                    final int tagaddr = (addr & TAG_MASK);
                    final int entry = (addr & ENTRY_MASK) >> ENTRY_SHIFT;

                    for (int i = 0; i < 4; i++) {
                        Sh2CacheLine line = ca.way[i][entry];
                        if ((line.v > 0) && (line.tag == tagaddr)) {
                            return getCachedData(line.data, addr & LINE_MASK, size);
                        }
                    }
                }
                return readMemoryUncached(memory, addr, size);
            case CACHE_DATA_ARRAY:
                assert ca.enable == 0 || ctx.twoWay == 1;
                return readBuffer(data_array, addr & (DATA_ARRAY_MASK >> ctx.twoWay), size);
            default:
                LOG.error("{} Unexpected cache read: {}, {}", cpu, th(addr), size);
                if (true) throw new RuntimeException();
                break;
        }
        return (int) size.getMask();
    }

    @Override
    public int cacheMemoryRead(int addr, Size size) {
        switch (addr & AREA_MASK) {
            case CACHE_USE: {
                if (ca.enable == 0) {
                    return readMemoryUncached(memory, addr, size);
                }
                final int tagaddr = (addr & TAG_MASK);
                final int entry = (addr & ENTRY_MASK) >> ENTRY_SHIFT;

                for (int i = 0; i < 4; i++) {
                    Sh2CacheLine line = ca.way[i][entry];
                    if ((line.v > 0) && (line.tag == tagaddr)) {
                        updateLru(i, ca.lru, entry);
//                        LOG.info("{} Cache read at {} {}", cpu, th(addr), size);
//                        if(addr == 0x15){
//                            System.out.println("here");
//                        }
                        if (verbose) LOG.info("{} Cache read at {} {}, val: {}", cpu, th(addr), size,
                                getCachedData(line.data, addr & LINE_MASK, size));
                        return getCachedData(line.data, addr & LINE_MASK, size);
                    }
                }
                // cache miss
                int lruway = selectWayToReplace(ctx.twoWay, ca.lru[entry]);
                final Sh2CacheLine line = ca.way[lruway][entry];
                updateLru(lruway, ca.lru, entry);
                line.tag = tagaddr;

                refillCache(line.data, addr);

                line.v = 1; //becomes valid
                return getCachedData(line.data, addr & LINE_MASK, size);
            }
            case CACHE_DATA_ARRAY:
                assert ca.enable == 0 || ctx.twoWay == 1;
//                LOG.info("{} Cache data array read: {}({}) {}, val: {}", cpu, th(addr),
//                        th(addr & (DATA_ARRAY_MASK >> ctx.twoWay)), size, th(readBuffer(data_array, addr & (DATA_ARRAY_MASK >> ctx.twoWay), size)));
                return readBuffer(data_array, addr & (DATA_ARRAY_MASK >> ctx.twoWay), size);
            case CACHE_PURGE:
                LOG.warn("{} CACHE_PURGE read: {}, {}", cpu, th(addr), size);
                break;
            case CACHE_ADDRESS_ARRAY:
                LOG.warn("{} CACHE_ADDRESS_ARRAY read: {}, {}", cpu, th(addr), size);
                break;
            default:
                LOG.error("{} Unexpected cache read: {}, {}", cpu, th(addr), size);
                if (true) throw new RuntimeException();
                break;
        }
        return (int) size.getMask();
    }

    @Override
    public void cacheMemoryWrite(int addr, int val, Size size) {
        switch (addr & AREA_MASK) {
            case CACHE_USE: {
                if (ca.enable == 0) {
                    writeMemoryUncached(memory, addr, val, size);
                    return;
                }
                final int tagaddr = (addr & TAG_MASK);
                final int entry = (addr & ENTRY_MASK) >> ENTRY_SHIFT;

                for (int i = 0; i < 4; i++) {
                    Sh2CacheLine line = ca.way[i][entry];
                    if ((line.v > 0) && (line.tag == tagaddr)) {
                        setCachedData(line.data, addr & LINE_MASK, val, size);
                        updateLru(i, ca.lru, entry);
                        if (verbose) LOG.info("Cache write at {}, val: {} {}", th(addr), th(val), size);
                        break;
                    }
                }
                // write through
                writeMemoryUncached(memory, addr, val, size);
            }
            break;
            case CACHE_DATA_ARRAY:
                assert ca.enable == 0 || ctx.twoWay == 1;
//                LOG.info("{} Cache data array write: {}({}) {}, val: {}", cpu, th(addr), th(addr & (DATA_ARRAY_MASK >> ctx.twoWay)), size, th(val));
                writeBuffer(data_array, addr & (DATA_ARRAY_MASK >> ctx.twoWay), val, size);
                break;
            case CACHE_PURGE://associative purge
            {
                assert size == Size.LONG;
                final int tagaddr = (addr & TAG_MASK);
                final int entry = (addr & ENTRY_MASK) >> ENTRY_SHIFT;
                //can purge more than one line
                for (int i = 0; i < CACHE_WAYS; i++) {
                    if (ca.way[i][entry].tag == tagaddr) {
                        //only v bit is changed, the rest of the data remains
                        ca.way[i][entry].v = 0;
                        Md32xRuntimeData.addCpuDelayExt(CACHE_PURGE_DELAY);
                    }
                }
                if (verbose) LOG.info("{} Cache purge: {}", cpu, th(addr));
            }
            break;
            case CACHE_ADDRESS_ARRAY:
                LOG.warn("{} CACHE_ADDRESS_ARRAY write: {}, {} {}", cpu, th(addr), th(val), size);
                break;
            default:
                LOG.error("{} Unexpected cache write: {}, {} {}", cpu, th(addr), th(val), size);
                if (true) throw new RuntimeException();
                break;
        }
    }

    @Override
    public CacheContext updateState(int value) {
        ctx.way = value >> 6;
        ctx.cachePurge = (value >> 4) & 1;
        ctx.twoWay = (value >> 3) & 1;
        ctx.dataReplaceDis = (value >> 2) & 1;
        ctx.instReplaceDis = (value >> 1) & 1;
        ctx.cacheEn = value & 1;
        //cache enable does not clear the cache
        ca.enable = ctx.cacheEn;
        if (verbose) LOG.info("{} CCR update: {}", cpu, ctx);
        if (ctx.cachePurge > 0) {
            cacheClear(ca);
            ctx.cachePurge = 0; //always reverts to 0
            value &= 0xEF;
        }
        ctx.ccr = value;
        return ctx;
    }

    @Override
    public ByteBuffer getDataArray() {
        return data_array;
    }

    //lru is updated
//when cache hit occurs during a read
//when cache hit occurs during a write
//when replacement occurs after a cache miss
    private static void updateLru(int way, int[] lruArr, int lruPos) {
        int lru = lruArr[lruPos];
        if (way == 3) {
            lru = lru | 0xb;//set bits 3, 1, 0
        } else if (way == 2) {
            lru = lru & 0x3E;//set bit 0 to 0
            lru = lru | 0x14;//set bits 4 and 2
        } else if (way == 1) {
            lru = lru | (1 << 5);//set bit 5
            lru = lru & 0x39;//unset bits 2 and 1
        } else {
            lru = lru & 0x7;//unset bits 5,4,3
        }
        lruArr[lruPos] = lru;
    }

    private static int selectWayToReplace(int twoWay, int lru) {
        if (twoWay > 0)//2-way mode
        {
            if ((lru & 1) == 1)
                return 2;
            else
                return 3;
        } else {
            if ((lru & 0x38) == 0x38)//bits 5, 4, 3 must be 1
                return 0;
            else if ((lru & 0x26) == 0x6)//bit 5 must be zero. bits 2 and 1 must be 1
                return 1;
            else if ((lru & 0x15) == 1)//bits 4, 2 must be zero. bit 0 must be 1
                return 2;
            else if ((lru & 0xB) == 0)//bits 3, 1, 0 must be zero
                return 3;
        }
        //should not happen
        throw new RuntimeException();
    }

    private void refillCache(int[] data, int addr) {
        Md32xRuntimeData.addCpuDelayExt(4);
        for (int i = 0; i < 16; i += 4) {
            //TODO afterBurner reads @ 0x4000
            try {
                int val = readMemoryUncached(memory, (addr & 0xFFFFFFF0) + i, Size.LONG);
                setCachedData(data, i & LINE_MASK, val, Size.LONG);
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
        }
    }

    @Override
    public CacheContext getCacheContext() {
        return ctx;
    }

    private void setCachedData(final int[] data, int addr, int val, Size size) {
        switch (size) {
            case BYTE:
                data[addr] = val;
                break;
            case WORD:
                data[addr] = val >> 8;
                data[(addr + 1) & LINE_MASK] = val;
                break;
            case LONG:
                data[addr] = ((val >> 24) & 0xFF);
                data[(addr + 1) & LINE_MASK] = ((val >> 16) & 0xFF);
                data[(addr + 2) & LINE_MASK] = ((val >> 8) & 0xFF);
                data[(addr + 3) & LINE_MASK] = ((val >> 0) & 0xFF);
                break;
            default:
                throw new RuntimeException();
        }
    }

    private int getCachedData(final int[] data, int addr, Size size) {
        switch (size) {
            case BYTE:
                return data[addr];
            case WORD:
                return ((data[addr]) << 8) | data[(addr + 1) & LINE_MASK];
            case LONG:
                return ((data[addr]) << 24) |
                        ((data[(addr + 1) & LINE_MASK]) << 16) |   //x-men
                        ((data[(addr + 2) & LINE_MASK]) << 8) |
                        ((data[(addr + 3) & LINE_MASK]) << 0);
            default:
                throw new RuntimeException();
        }
    }
}