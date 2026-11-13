qt_add_library(FlightDisplayModule STATIC)

qt_add_qml_module(FlightDisplayModule
    URI QGroundControl.FlightDisplay
    VERSION 1.0
    RESOURCE_PREFIX /qml
    QML_FILES
        CustomGuidedActionsController.qml
        DefaultChecklist.qml
        FixedWingChecklist.qml
        FlightDisplayViewDummy.qml
        FlightDisplayViewGStreamer.qml
        FlightDisplayViewQtMultimedia.qml
        FlightDisplayViewUVC.qml
        FlightDisplayViewVideo.qml
        FlyView.qml
        FlyViewAdditionalActionsButton.qml
        FlyViewAdditionalActionsList.qml
        FlyViewAdditionalActionsPanel.qml
        FlyViewAdditionalCustomActionsList.qml
        FlyViewBottomRightRowLayout.qml
        FlyViewCustomLayer.qml
        FlyViewInsetViewer.qml
        FlyViewMap.qml
        FlyViewMissionCompleteDialog.qml
        FlyViewPreFlightChecklistPopup.qml
        FlyViewToolStrip.qml
        FlyViewToolStripActionList.qml
        FlyViewTopRightColumnLayout.qml
        FlyViewTopRightPanel.qml
        GarudaRetractablePanel.qml
        GarudaPlannerHud.qml
        GarudaFlightInformation.qml
        GarudaFlightActions.qml
        GarudaRightPanels.qml
        FlyViewVideo.qml
        FlyViewWidgetLayer.qml
        GripperMenu.qml
        GuidedActionConfirm.qml
        GuidedActionGripper.qml
        GuidedActionLand.qml
        GuidedActionPause.qml
        GuidedActionRTL.qml
        GuidedActionsController.qml
        GuidedActionTakeoff.qml
        GuidedToolStripAction.qml
        GuidedValueSlider.qml
        MultiRotorChecklist.qml
        MultiVehicleList.qml
        ObstacleDistanceOverlay.qml
        ObstacleDistanceOverlayMap.qml
        ObstacleDistanceOverlayVideo.qml
        OnScreenGimbalController.qml
        PreFlightBatteryCheck.qml
        PreFlightCheckList.qml
        PreFlightCheckListShowAction.qml
        PreFlightGPSCheck.qml
        PreFlightRCCheck.qml
        PreFlightSensorsHealthCheck.qml
        PreFlightSoundCheck.qml
        ProximityRadarValues.qml
        ProximityRadarVideoView.qml
        QGCVideoBackground.qml
        RoverChecklist.qml
        SubChecklist.qml
        TelemetryValuesBar.qml
        TerrainProgress.qml
        VehicleWarnings.qml
        VirtualJoystick.qml
    NO_PLUGIN # Required by Qt 6.6.3. TODO: Remove when no longer supported.
)

target_sources(${CMAKE_PROJECT_NAME} PRIVATE GarudaActionsController.cc GarudaActionsController.h)
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
