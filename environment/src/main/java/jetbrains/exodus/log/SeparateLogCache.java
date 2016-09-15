/**
 * Copyright 2010 - 2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.log;

import jetbrains.exodus.ArrayByteIterable;
import jetbrains.exodus.core.dataStructures.ConcurrentLongObjectCache;
import jetbrains.exodus.core.dataStructures.LongObjectCache;
import jetbrains.exodus.core.dataStructures.LongObjectCacheBase;
import org.jetbrains.annotations.NotNull;

import static jetbrains.exodus.core.dataStructures.LongObjectCacheBase.CriticalSection;
import static jetbrains.exodus.core.dataStructures.LongObjectCacheBase.DEFAULT_SIZE;

final class SeparateLogCache extends LogCache {

    @NotNull
    private final LongObjectCacheBase<ArrayByteIterable> pagesCache;

    SeparateLogCache(final long memoryUsage, final int pageSize, final boolean nonBlocking) {
        super(memoryUsage, pageSize);
        final int pagesCount = (int) (memoryUsage / (pageSize +
                /* each page consumes additionally nearly 80 bytes in the cache */ 80));
        pagesCache = nonBlocking ?
                new ConcurrentLongObjectCache<ArrayByteIterable>(pagesCount, CONCURRENT_CACHE_GENERATION_COUNT) :
                new LongObjectCache<ArrayByteIterable>(pagesCount);
    }

    SeparateLogCache(final int memoryUsagePercentage, final int pageSize, final boolean nonBlocking) {
        super(memoryUsagePercentage, pageSize);
        if (memoryUsage == Long.MAX_VALUE) {
            pagesCache = nonBlocking ?
                    new ConcurrentLongObjectCache<ArrayByteIterable>(DEFAULT_SIZE, CONCURRENT_CACHE_GENERATION_COUNT) :
                    new LongObjectCache<ArrayByteIterable>();
        } else {
            final int pagesCount = (int) (memoryUsage / (pageSize +
                    /* each page consumes additionally nearly 80 bytes in the cache */ 80));
            pagesCache = nonBlocking ?
                    new ConcurrentLongObjectCache<ArrayByteIterable>(pagesCount, CONCURRENT_CACHE_GENERATION_COUNT) :
                    new LongObjectCache<ArrayByteIterable>(pagesCount);
        }
    }

    @Override
    public void clear() {
        try (CriticalSection ignored = pagesCache.newCriticalSection()) {
            pagesCache.clear();
        }
    }

    @Override
    public float hitRate() {
        return pagesCache.hitRate();
    }

    @Override
    void cachePage(@NotNull final Log log, final long pageAddress, @NotNull final ArrayByteIterable page) {
        cachePage(pageAddress >> pageSizeLogarithm, page);
    }

    @Override
    @NotNull
    ArrayByteIterable getPage(@NotNull final Log log, final long pageAddress) {
        ArrayByteIterable page = log.getHighPage(pageAddress);
        if (page != null) {
            return page;
        }
        final long cacheKey = pageAddress >> pageSizeLogarithm;
        page = pagesCache.tryKeyLocked(cacheKey);
        if (page != null) {
            return page;
        }
        page = readFullPage(log, pageAddress);
        cachePage(cacheKey, page);
        return page;
    }

    @Override
    protected void removePage(@NotNull final Log log, final long pageAddress) {
        try (CriticalSection ignored = pagesCache.newCriticalSection()) {
            pagesCache.remove(pageAddress >> pageSizeLogarithm);
        }
    }

    private void cachePage(final long cacheKey, @NotNull final ArrayByteIterable page) {
        try (CriticalSection ignored = pagesCache.newCriticalSection()) {
            if (pagesCache.getObject(cacheKey) == null) {
                pagesCache.cacheObject(cacheKey, postProcessTailPage(page));
            }
        }
    }
}
