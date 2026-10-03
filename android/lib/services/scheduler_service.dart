import 'dart:async';



class SchedulerService {



Timer? timer;



void startDailyTask({

required int hour,

required int minute,

required Function callback,

}){


timer?.cancel();



timer = Timer.periodic(

const Duration(minutes:1),

(t){


final now =
DateTime.now();



if(
now.hour == hour &&
now.minute == minute
){

callback();

}


});


}




void stop(){

timer?.cancel();

}


}