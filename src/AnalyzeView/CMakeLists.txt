target_sources(${CMAKE_PROJECT_NAME}
    PRIVATE
        ExifParser.cc
        ExifParser.h
        GeoTagController.cc
        GeoTagController.h
        GeoTagWorker.cc
        GeoTagWorker.h
        LogDownloadController.cc
        LogDownloadController.h
        LogEntry.cc
        LogEntry.h
        MAVLinkChartController.cc
        MAVLinkChartController.h
        MAVLinkConsoleController.cc
        MAVLinkConsoleController.h
        MAVLinkInspectorController.cc
        MAVLinkInspectorController.h
        MAVLinkMessage.cc
        MAVLinkMessage.h
        MAVLinkMessageField.cc
        MAVLinkMessageField.h
        MAVLinkSystem.cc
        MAVLinkSystem.h
        PX4LogParser.cc
        PX4LogParser.h
        ULogParser.cc
        ULogParser.h
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

qt_add_library(AnalyzeViewModule STATIC)

option(GARUDA_UAV_LOG_VIEWER "Embed the UAV Log Viewer on Windows" ${WIN32})
set(_garuda_analyze_qml)
if(GARUDA_UAV_LOG_VIEWER AND NOT ANDROID AND NOT IOS)
    find_package(Qt6 REQUIRED GLOBAL COMPONENTS WebEngineQuick)
    target_sources(${CMAKE_PROJECT_NAME} PRIVATE UAVLogViewerServer.cc UAVLogViewerServer.h)
    target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE Qt6::WebEngineQuick)
    target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE GARUDA_UAV_LOG_VIEWER)
    # The executable is declared in the top-level directory, so apply this
    # source property in that target's scope rather than this subdirectory.
    set_source_files_properties("${CMAKE_CURRENT_SOURCE_DIR}/UAVLogViewerServer.cc"
        TARGET_DIRECTORY ${CMAKE_PROJECT_NAME} PROPERTIES COMPILE_DEFINITIONS
        "GARUDA_UAVLOGVIEWER_DIST=\"${CMAKE_SOURCE_DIR}/third_party/UAVLogViewer/dist\"")
    list(APPEND _garuda_analyze_qml UAVLogViewerPage.qml)
endif()

qt_add_qml_module(AnalyzeViewModule
    URI QGroundControl.AnalyzeView
    VERSION 1.0
    RESOURCE_PREFIX /qml
    QML_FILES
        AnalyzeView.qml
        GeoTagPage.qml
        LogDownloadPage.qml
        MAVLinkConsolePage.qml
        MAVLinkInspectorPage.qml
        VibrationPage.qml
        ${_garuda_analyze_qml}
    NO_PLUGIN
)

#===========================================================================#

CPMAddPackage(
    NAME ulog_cpp
    GITHUB_REPOSITORY PX4/ulog_cpp
    GIT_TAG main
)

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(ulog_cpp PRIVATE -Wno-unknown-warning-option)
endif()

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE ulog_cpp::ulog_cpp)
