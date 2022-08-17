## 22.0817-PD-SNAPSHOT
* (pre-drc branch)
* 32x: partial sh2 reset impl, improve homebrew bios handling
* 32x: tweak vpd rendering
* 32x: improve HEN handling
* 32x: improve divUnit
* helios: migrate to tinylog
* helios: require jdk 17 or later
* sh2: ignore most delays for now, tweak cache mem reads
* sh2: change sh2 cycle counting, fix prefetch invalidation on cache purge
* sh2: handle cache address array r/w access
* sh2: prevent interrupts in a delay slot
* sh2: DMA cannot write to cache
* sh2: cache and prefetcher fixes

## 22.0516-PD-SNAPSHOT
* (pre-drc branch)
* 32x: fix bug on address masking
* 32x: fix sh reg handling
* 32x: fix NTSC_H40_V30 mode
* 32x: sh2 prefetch fixes, sh2 timing fixes, enable FM
* 32x: tweak sh2 cycles
* sh2: fix ADDC, rewrite a few insts
* sh2: only autoclear one type of interrupts

## 22.0315-SNAPSHOT
* implement sh2 cache
* add FM, RV bit handling
* fix joypad detection

## 22.0223-SNAPSHOT
* more stuff working, lots broken still

## 21.1214-SNAPSHOT
* first public release, not much working yet!
