# This file is made available under Elastic License 2.0.
# This file is based on code available under the Apache license here:
#   https://github.com/apache/orc/tree/main/c++/CMakeLists.txt

# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

if (DEFINED ENV{STARROCKS_HOME}) # starrocks: orc prioritize project search path
include_directories (
  BEFORE
  ${CMAKE_CURRENT_BINARY_DIR}/include
  "include"
  )
else()
include_directories (
  ${CMAKE_CURRENT_BINARY_DIR}/include
  "include"
  )
endif()

add_subdirectory(include)
add_subdirectory(src)

if (BUILD_CPP_TESTS)
  add_subdirectory(test)
endif ()
