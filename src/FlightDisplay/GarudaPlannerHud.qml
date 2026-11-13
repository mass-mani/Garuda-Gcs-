// Native Qt implementation; upstream references documented in MISSION-PLANNER-INTEGRATION.md.
import QtQuick
import QGroundControl
Item {
    id: root
    property bool amslAltitude: false
    property var vehicle: QGroundControl.multiVehicleManager.activeVehicle
    readonly property bool live: vehicle !== null && !vehicle.vehicleLinkManager.communicationLost
    readonly property real roll: live ? Number(vehicle.roll.rawValue) : NaN
    readonly property real pitch: live ? Number(vehicle.pitch.rawValue) : NaN
    readonly property real heading: live ? Number(vehicle.heading.rawValue) : NaN
    readonly property real speed: live ? Number(vehicle.groundSpeed.rawValue) : NaN
    readonly property real altitude: live ? Number(amslAltitude ? vehicle.altitudeAMSL.rawValue : vehicle.altitudeRelative.rawValue) : NaN
    readonly property real airspeed: live ? Number(vehicle.airSpeed.rawValue) : NaN
    readonly property real climb: live ? Number(vehicle.climbRate.rawValue) : NaN
    readonly property var battery: live && vehicle.batteries.count ? vehicle.batteries.get(0) : null
    function display(value, digits) { return isFinite(value) ? Number(value).toFixed(digits) : "—" }
    clip: true
    Canvas {
        id: horizon; anchors.fill: parent
        onPaint: {
            const ctx = getContext("2d"); ctx.reset()
            const w=width, h=height, cx=w/2, cy=h*.48
            const r=isFinite(root.roll)?root.roll:0, p=isFinite(root.pitch)?root.pitch:0, unit=h/70
            ctx.save();ctx.translate(cx,cy);ctx.rotate(-r*Math.PI/180)
            const y=p*unit
            const sky=ctx.createLinearGradient(0,-h,0,y)
            sky.addColorStop(0,"#527de0");sky.addColorStop(1,"#a8d5e8")
            ctx.fillStyle=sky;ctx.fillRect(-w*3,-h*3,w*6,h*3+y)
            const ground=ctx.createLinearGradient(0,y,0,h)
            ground.addColorStop(0,"#9aa72b");ground.addColorStop(1,"#536716")
            ctx.fillStyle=ground;ctx.fillRect(-w*3,y,w*6,h*3)
            ctx.strokeStyle="white";ctx.lineWidth=1.3
            ctx.beginPath();ctx.moveTo(-w*3,y);ctx.lineTo(w*3,y);ctx.stroke()
            ctx.font=Math.max(10,h*.052)+"px sans-serif";ctx.textAlign="center";ctx.fillStyle="white"
            for(let angle=-90;angle<=90;angle+=5) {
                if(angle===0)continue
                const ly=(p-angle)*unit, len=angle%10===0?w*.15:w*.07
                if(Math.abs(ly)>h)continue
                ctx.beginPath();ctx.moveTo(-len,ly);ctx.lineTo(len,ly);ctx.stroke()
                if(angle%10===0){ctx.fillText(String(angle),-len-16,ly+4);ctx.fillText(String(angle),len+16,ly+4)}
            }
            ctx.restore();ctx.strokeStyle="#ffdf42";ctx.lineWidth=3
            ctx.beginPath();ctx.moveTo(cx-w*.19,cy);ctx.lineTo(cx-w*.07,cy);ctx.lineTo(cx,cy+7);ctx.lineTo(cx+w*.07,cy);ctx.lineTo(cx+w*.19,cy);ctx.stroke()
            ctx.strokeStyle="white";ctx.lineWidth=1.5
            const radius=Math.min(w*.29,h*.35)
            for(let bank of [-60,-45,-30,-20,-10,0,10,20,30,45,60]) {
                const a=(bank-90)*Math.PI/180
                ctx.beginPath();ctx.moveTo(cx+Math.cos(a)*radius,cy+Math.sin(a)*radius);ctx.lineTo(cx+Math.cos(a)*(radius+7),cy+Math.sin(a)*(radius+7));ctx.stroke()
            }
            const a=(-r-90)*Math.PI/180
            ctx.fillStyle="#ff4242";ctx.beginPath();ctx.arc(cx+Math.cos(a)*(radius-5),cy+Math.sin(a)*(radius-5),4,0,Math.PI*2);ctx.fill()
            ctx.fillStyle="#bb102030";ctx.fillRect(0,0,w,h*.12)
            ctx.fillStyle="white";ctx.font=Math.max(11,h*.055)+"px sans-serif";ctx.textAlign="center"
            if(isFinite(root.heading)) for(let delta=-60;delta<=60;delta+=15) {
                const value=(Math.floor(root.heading/15)*15+delta+360)%360
                const relative=((value-root.heading+540)%360)-180, x=cx+relative*w/110
                if(x<10||x>w-10)continue
                const cardinals={0:"N",90:"E",180:"S",270:"W"}
                ctx.fillText(cardinals[value]||String(value),x,h*.085)
            }
            ctx.strokeStyle="#ffdf42";ctx.strokeRect(cx-21,1,42,h*.11)
            for(let side=0;side<2;side++) {
                const value=side===0?root.speed:root.altitude, x=side===0?0:w-48
                ctx.fillStyle="#66071521";ctx.fillRect(x,h*.2,48,h*.53)
                ctx.fillStyle="white";ctx.font=Math.max(10,h*.046)+"px sans-serif"
                if(isFinite(value))for(let step=-2;step<=2;step++)ctx.fillText(String(Math.round(value)+step*5),x+24,cy-step*h*.1+4)
                ctx.fillStyle="#ed071521";ctx.fillRect(x,cy-12,48,24)
                ctx.fillStyle="#ffdf42";ctx.fillText(root.display(value,1),x+24,cy+4)
            }
        }
        Connections {
            target: root
            function onRollChanged() { horizon.requestPaint() }
            function onPitchChanged() { horizon.requestPaint() }
            function onHeadingChanged() { horizon.requestPaint() }
            function onSpeedChanged() { horizon.requestPaint() }
            function onAltitudeChanged() { horizon.requestPaint() }
        }
    }
    Text {
        anchors.right:parent.right; y:parent.height*.13; color:"white"
        text:root.amslAltitude ? qsTr("AMSL m") : qsTr("REL m"); font.pixelSize:Math.max(10,parent.height*.046)
    }
    Text {
        anchors.horizontalCenter: parent.horizontalCenter; y: parent.height*.24
        text: !root.live ? qsTr("NO TELEMETRY") : (root.vehicle.armed ? qsTr("ARMED") : qsTr("DISARMED"))
        color: root.live && root.vehicle.armed ? "#74ff9b" : "#ff5c56"
        font.bold: true; font.pixelSize: Math.max(12,parent.height*.075)
        style: Text.Outline; styleColor: "#182532"
    }
    Rectangle {
        anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
        height: parent.height*.25; color: "#c0071521"
        Column {
            anchors.fill: parent; anchors.margins: 3
            Text { color:"white"; font.pixelSize:Math.max(10,root.height*.047); text:"AS "+root.display(root.airspeed,1)+"   GS "+root.display(root.speed,1)+" m/s   VS "+root.display(root.climb,1)+" m/s" }
            Text { color:"#ffdf42"; font.pixelSize:Math.max(10,root.height*.047); text:root.live ? root.vehicle.flightMode+"   GPS: "+root.vehicle.gps.lock.enumOrValueString+" / "+root.vehicle.gps.count.valueString : qsTr("Connect a vehicle for live flight data") }
            Text { color:"white"; font.pixelSize:Math.max(10,root.height*.047); text:root.battery ? "BAT "+root.battery.voltage.valueString+" V   "+root.battery.current.valueString+" A   "+root.battery.percentRemaining.valueString+" %" : "BAT — V   — A   — %" }
            Text { width:parent.width; elide:Text.ElideRight; color:"#ff7973"; font.pixelSize:Math.max(10,root.height*.047); text:root.live ? (root.vehicle.prearmError || (root.vehicle.readyToFlyAvailable ? (root.vehicle.readyToFly ? qsTr("Ready to arm") : qsTr("Not ready to arm")) : "")) : "" }
        }
    }
}
