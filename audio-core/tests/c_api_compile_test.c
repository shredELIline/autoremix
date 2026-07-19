#include "autoremix/audio_core_c.h"

int ar_c_header_compile_probe(void) {
  ar_bridge_request_t request = {0};
  ar_bridge_result_t result = {0};
  request.struct_size = (uint32_t)sizeof(request);
  result.struct_size = (uint32_t)sizeof(result);
  return request.struct_size > 0U && result.struct_size > 0U &&
                 ar_audio_core_abi_version() == AR_AUDIO_CORE_ABI_VERSION
             ? 1
             : 0;
}
