/*
    CPERP Clock
    Created by Andi - 20220314 - Request pak Sugi
    jQuery Masked Input Plugin
    Version: 1.0.1
*/
$("document").ready(function(){

	var serverTime;
	var localTime;
	var timeDiff;

	myInterval = setInterval(tryToTriggerClock, 1000);
	
	function tryToTriggerClock () {
		console.log("tryToTriggerClock..");
		
		if($(".desktop-header-cperpClock").length > 0) {
		
			serverTime = parseInt($(".desktop-header-cperpClock").html()); //this would come from the server
			localTime = +Date.now();
			timeDiff = serverTime - localTime;
		
			console.log("startTime..");
			$(".desktop-header-cperpClock").css("display", "block");
			
			startTime(); // load for the first time
			clearInterval(myInterval);
		}
	}

	function startTime() {
		
		var realtime = +Date.now();
		var date = new Date(realtime);
		
		var h = date.getHours();
		var m = date.getMinutes();
		var s = date.getSeconds();
	
		m = checkTime(m);
		s = checkTime(s);
		  
		var clockWillDisplay  = h + ":" + m + ":" + s;
		  
	    $(".desktop-header-cperpClock").html(clockWillDisplay);
	    
	    setTimeout(startTime, 1000);
	}
	
	function checkTime(i) {
	  if (i < 10) {i = "0" + i};  // add zero in front of numbers < 10
	  return i;
	}
});