// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "datasegment.hpp"
#include "memblockboundscheck_d.h"

namespace vespamalloc::segment {

template class DataSegment<MemBlockBoundsCheck>;

}
