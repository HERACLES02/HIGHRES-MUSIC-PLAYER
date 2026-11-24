cmake_minimum_required(VERSION 3.22.1)
project(local_music_usb LANGUAGES C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(vendor/flac flac-build EXCLUDE_FROM_ALL)
set(VENDOR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/libusb)
option(USB_SELF_TEST "Build isolated app runtime selftest backend" OFF)
option(LOCALMUSIC_EXPERIMENTAL_DIRECT "Enable explicitly authorized personal Direct build" OFF)
if(NOT USB_SELF_TEST)
add_library(usb-1.0 SHARED
    ${VENDOR}/libusb/core.c ${VENDOR}/libusb/descriptor.c
    ${VENDOR}/libusb/hotplug.c ${VENDOR}/libusb/io.c
    ${VENDOR}/libusb/sync.c ${VENDOR}/libusb/strerror.c
    ${VENDOR}/libusb/os/linux_usbfs.c ${VENDOR}/libusb/os/events_posix.c
    ${VENDOR}/libusb/os/threads_posix.c ${VENDOR}/libusb/os/linux_netlink.c)
target_include_directories(usb-1.0 PRIVATE ${VENDOR}/android ${VENDOR}/libusb ${VENDOR}/libusb/os)
target_compile_options(usb-1.0 PRIVATE -fvisibility=hidden -pthread)
target_link_libraries(usb-1.0 PRIVATE log)
add_library(usb_experiment SHARED diagnostic.cpp pcm_decode.cpp
    experimental/flac_source_owner.cpp experimental/flac_stream_reader.cpp)
target_include_directories(usb_experiment PRIVATE ${VENDOR}/libusb)
target_compile_options(usb_experiment PRIVATE -Wall -Wextra -Werror)
target_compile_definitions(usb_experiment PRIVATE LOCALMUSIC_EXPERIMENTAL_DIRECT=$<BOOL:${LOCALMUSIC_EXPERIMENTAL_DIRECT}>)
target_link_libraries(usb_experiment PRIVATE usb-1.0 FLAC)
target_link_options(usb-1.0 PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(usb_experiment PRIVATE "-Wl,-z,max-page-size=16384")
endif()

# Separate opt-in selftest APK only. Reuses production JNI and proven fake ISO
# transport; it must never link the physical USB backend.
if(USB_SELF_TEST)
    add_library(usb_experiment_fake SHARED tests/service_fake_backend.cpp pcm_decode.cpp
        experimental/flac_source_owner.cpp experimental/flac_stream_reader.cpp)
    target_include_directories(usb_experiment_fake PRIVATE ${VENDOR}/libusb)
    target_compile_options(usb_experiment_fake PRIVATE -Wall -Wextra -Werror)
    target_link_options(usb_experiment_fake PRIVATE -Wl,--no-undefined,--Bsymbolic-functions,--wrap=fread,--wrap=malloc,--wrap=calloc,--wrap=realloc)
    target_link_libraries(usb_experiment_fake PRIVATE FLAC)
endif()

# Isolated device-free check; excluded from the normal diagnostic/app build.
option(BUILD_USB_BACKEND_LIFETIME_TEST "Build device-free adapter/backend lifetime checks" OFF)
if(BUILD_USB_BACKEND_LIFETIME_TEST)
    add_executable(usb_backend_lifetime_test tests/usb_backend_lifetime_test.cpp
        experimental/flac_source_owner.cpp experimental/flac_stream_reader.cpp)
    target_include_directories(usb_backend_lifetime_test PRIVATE ${VENDOR}/libusb)
    target_compile_options(usb_backend_lifetime_test PRIVATE -Wall -Wextra -Werror)
    target_link_options(usb_backend_lifetime_test PRIVATE -Wl,--no-undefined,--wrap=libusb_init_context,--wrap=libusb_exit,--wrap=libusb_alloc_transfer,--wrap=libusb_free_transfer,--wrap=libusb_submit_transfer,--wrap=libusb_cancel_transfer,--wrap=libusb_open,--wrap=libusb_wrap_sys_device,--wrap=libusb_claim_interface,--wrap=fopen,--wrap=fdopen,--wrap=fclose,--wrap=fread)
    target_link_libraries(usb_backend_lifetime_test PRIVATE usb-1.0 FLAC dl)
endif()
