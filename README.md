MaximaPool
==========

Pooling solution for starting up maxima processes so that [moodle-qtype_stack](https://github.com/sangwinc/moodle-qtype_stack/) does not need to wait for them, this may save over 250ms each time you have to call maxima.

## Basic overview

Stack being PHP-application it has no way of handling pooling of maxima processes (i.e. no threads), maximapool is a Java-servlet doing just that and only that.

MaximaPool does not check what it inputs to the processes it is running nor does it really care what it is running so you may also use it for other programs but most importantly you MUST MAKE SURE IT IS SECURED no public access and so on.


## How it works

Servlet keeps a set of processes running and uses them to evaluate commands it receives as requests, the servlet responds by outputting the output of the process one may define a timeout for the evaluation of the commands and in the case the servlet returns whatever the process has outputted at that point (should the timeout be reached the response code will be 416 otherwise the normal code 200 is used). The size of the pool kept may adapt to the frequency of the requests and the frequency can be throthled.

Processes have a lifetime and once that is up they will be ended and new ones started should the pool require new ones. 

The main load caused by the servlet is due to the frequent updating of the frequency estimate and simultaneous checking for old processes, this load may be tuned by changing the frequency of these updates but that may cause problems with demand spikes.

The servlet has a monitoring interface that shows some information about the current state and allows direct test inputs. To access the monitoring interface just make a GET-request for the servlet.


## Installation

Get [tomcat6](http://tomcat.apache.org/) or some other servlet-container supportting Java 1.6. 

Get [maxima](http://maxima.sourceforge.net/) it is needed for Stack.

Get [ant](http://ant.apache.org/) to build the servlet.

Start by installing Stack as normal and make sure that it works with the maxima you have installed.

Download or clone the MaximaPool files somewhere and start working with the maximapool.conf file once you have tuned the configuration to match your needs just run ant to build the servlet.

Once the servlet has been built deploy the MaximaPool.war file to your servlet-container, with tomcat just copy it to the webapps-directory.

With Stack check that tomcat or whatever user is running the servlet-container and thus maxima has the correct permissions to write to Stacks work directorys. Otherwise plots cannot be generated. Or setup the servlet in remote operating mode so that it can transmit possible plots in the responses.

Open localhost:8080/MaximaPool/MaximaPool or whatever url you have configured and test the servlet by inputting something.

Change Stack to use MaximaPool and set the maxima-command in Stack to match the url where the servlet is running. Or use the Server mode with if you cannot setup the system so that it would write plots to the correct place.

You may wish to clear the caches to check that the servlet actually works otherwise you may just get old values from cache instead of new ones from the servlet.

## License

MaximaPool is Licensed under whatever license [moodle-qtype_stack](https://github.com/sangwinc/moodle-qtype_stack/) is licensed. Currently, GNU General Public, License Version 3.

