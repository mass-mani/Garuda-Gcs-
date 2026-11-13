if(QGC_NO_SERIAL_LINK)
    return()
endif()

target_sources(${CMAKE_PROJECT_NAME}
    PRIVATE
        GPSManager.cc
        GPSManager.h
        GPSProvider.cc
        GPSProvider.h
        GPSRtk.cc
        GPSRtk.h
        GPSRTKFactGroup.cc
        GPSRTKFactGroup.h
        RTCMMavlink.cc
        RTCMMavlink.h
        satellite_info.h
        sensor_gnss_relative.h
        sensor_gps.h
)

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE Qt6::Core)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

#===========================================================================#

CPMAddPackage(
    NAME px4-gpsdrivers
    GITHUB_REPOSITORY PX4/PX4-GPSDrivers
    GIT_TAG 2a5e78abfb34c91e1340d8136822397462f088d9
    SOURCE_SUBDIR src
)

set(GPS_DRIVERS_SOURCE_DIR "${px4-gpsdrivers_SOURCE_DIR}/src")
if(MSVC)
    # Build a local compatibility copy; leave the shared Android dependency intact.
    set(GPS_DRIVERS_SOURCE_DIR "${CMAKE_CURRENT_BINARY_DIR}/msvc-gpsdrivers")
    file(COPY "${px4-gpsdrivers_SOURCE_DIR}/src/" DESTINATION "${GPS_DRIVERS_SOURCE_DIR}")
    file(READ "${GPS_DRIVERS_SOURCE_DIR}/ubx.h" _ubx_header)
    string(REPLACE
        "\tstruct CfgValsetItem {\n\t\tuint32_t key;\n\t\tuint8_t value;\n\t} __attribute__((packed));"
        "#pragma pack(push, 1)\n\tstruct CfgValsetItem {\n\t\tuint32_t key;\n\t\tuint8_t value;\n\t};\n#pragma pack(pop)\n\tstatic_assert(sizeof(CfgValsetItem) == 5 && alignof(CfgValsetItem) == 1, \"UBX configuration item must remain packed\");"
        _ubx_header "${_ubx_header}"
    )
    file(CONFIGURE OUTPUT "${GPS_DRIVERS_SOURCE_DIR}/ubx.h" CONTENT "${_ubx_header}" @ONLY)
endif()

file(GLOB GPS_DRIVERS_SOURCES "${GPS_DRIVERS_SOURCE_DIR}/*")
target_sources(${CMAKE_PROJECT_NAME}
    PRIVATE
        definitions.h
        ${GPS_DRIVERS_SOURCES}
)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE GPS_DEFINITIONS_HEADER=<${CMAKE_CURRENT_SOURCE_DIR}/definitions.h>)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${GPS_DRIVERS_SOURCE_DIR})
