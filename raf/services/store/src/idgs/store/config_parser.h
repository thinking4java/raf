
/*
Copyright (c) <2013>, Intel Corporation All Rights Reserved.

The source code, information and material ("Material") contained herein is owned by Intel Corporation or its suppliers or licensors, and title to such Material remains with Intel Corporation or its suppliers or licensors. The Material contains proprietary information of Intel or its suppliers and licensors. The Material is protected by worldwide copyright laws and treaty provisions. No part of the Material may be used, copied, reproduced, modified, published, uploaded, posted, transmitted, distributed or disclosed in any way without Intel's prior express written permission. No license under any patent, copyright or other intellectual property rights in the Material is granted to or conferred upon you, either expressly, by implication, inducement, estoppel or otherwise. Any license under such intellectual property rights must be express and approved by Intel in writing.

Unless otherwise agreed by Intel in writing, you may not remove or alter this notice or any other notice embedded in Materials by Intel or Intel’s suppliers or licensors in any way.
*/
#pragma once

#include "idgs/result_code.h"
#include "idgs/store/pb/store_config.pb.h"

namespace idgs {
namespace store {

/// Parser class. <br>
/// To parse config file of data store. The config file is about each store info. <br>
class StoreConfigParser {
public:

  /// @brief  Make the config file structuring.
  /// @param  path    The config file path.
  /// @param  config  Structuring of store config.
  /// @return Status code of result.
  static ResultCode parseStoreConfigFromFile(const std::string& path, pb::DataStoreConfig* config);

  /// @brief  Make the config content structuring.
  /// @param  path    The content of config.
  /// @param  config  Structuring of store config.
  /// @return Status code of result.
  static ResultCode parseStoreConfigFromString(const std::string& content, pb::DataStoreConfig* config);

  /// @brief  Make the config content structuring.
  /// @param  path    The content of config.
  /// @param  config  Structuring of store config.
  /// @return Status code of result.
  static ResultCode parseStoreConfig(pb::DataStoreConfig* config);

private:
  StoreConfigParser(const StoreConfigParser& other) = delete;
  StoreConfigParser(StoreConfigParser&& other) = delete;
  StoreConfigParser& operator()(const StoreConfigParser& other) = delete;
  StoreConfigParser& operator()(StoreConfigParser&& other) = delete;
};
// class ConfigParser

}// namespace store
} // namespace idgs
