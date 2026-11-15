/**
 * Game-free algorithms of the retained render tier: section keys, volume
 * bucketing, diff classification, merged outlines and faces, heatmap cells, colormaps, vertex
 * writing and the GPU arena sub-allocator. Everything here runs on worker threads over plain data
 * and abstract block ids; the game-specific half lives in {@code common}.
 */
@NullMarked
package io.github.pix3lpirat3.mfbridge.core.retained;

import org.jspecify.annotations.NullMarked;
