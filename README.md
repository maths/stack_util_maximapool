MaximaPool
==========

MaximaPool has been designed to work in conjunction with the STACK question type for the Moodle quiz. See [moodle-qtype_stack](https://github.com/sangwinc/moodle-qtype_stack/).
However, it may be of independent interest to other projects which make use of the CAS [maxima](http://maxima.sourceforge.net/).

One of the problems with using Maxima is that it is relatively slow to start up. A pooling solution for starting up maxima processes means that do not need to wait for them.  This may save over 250ms each time you have to call maxima.

## Basic overview

STACK is a PHP-application and hence it has no way of handling pooling of maxima processes (i.e. no threads), maximapool is a Java-servlet doing just that and only that.

MaximaPool does not check what it inputs to the processes it is running nor does it really care what it is running so you may also use it for other programs but most importantly you MUST MAKE SURE IT IS SECURED and that there is absolutely no public access, and so on.

## How it works

The servlet keeps a set of processes running and uses them to evaluate commands it
receives as requests, the servlet responds by outputting the output of the process
one may define a timeout for the evaluation of the commands and in the case the
servlet returns whatever the process has outputted at that point (should the
timeout be reached the response code will be 416 otherwise the normal code 200
is used). The size of the pool kept may adapt to the frequency of the requests
and the frequency can be throttled.

Processes have a lifetime and once that is up they will be ended and new ones
started, should the pool require new ones.

The main load caused by the servlet is due to the frequent updating of the
frequency estimate and simultaneous checking for old processes, this load may
be tuned by changing the frequency of these updates but that may cause problems
with demand spikes.

The servlet has a monitoring interface that shows some information about the
current state and allows direct test inputs. To access the monitoring interface
just make a GET-request for the servlet.


## Installation

1. Get [tomcat6](http://tomcat.apache.org/) or some other servlet-container supporting Java 1.6.
2. Get [ant](http://ant.apache.org/) to build the servlet.
3. Install STACK as normal and make sure that it works with the [maxima](http://maxima.sourceforge.net/) you have installed.  
4. Download or clone the MaximaPool files somewhere. 
5. Copy `maximapool-example.conf` to `maximapool.conf` and edit it to have the correct configuration for your server. 
6. Run `ant` to build the servlet.
7. Deploy the `MaximaPool.war` file to your servlet-container, with tomcat just copy it to the webapps-directory.
8. With STACK check that tomcat (or whatever user is running the servlet-container) and thus maxima has the correct permissions to write to STACK's working directories. Otherwise plots cannot be generated. 
9. The page `localhost:8080/MaximaPool/MaximaPool` (or whatever url you have configured) contains a minimal test interface.  You can check the servlet by inputting something there.
10. Navigate the web-browser to the `Home > Site administration > Plugins > Question types > STACK`
  1. For "Platform type" choose "Server".
  2. For the "Maxima command" choose the URL of your remote server (which might be the local machine), e.g. `http://stack.somwhere.dom.zz:8080/MaximaPool/MaximaPool`

Remember to clear the cache to check that the servlet actually works otherwise you may just get old values from cache instead of new ones from the servlet.

You are likely to want to use the [optimized version](https://github.com/maths/moodle-qtype_stack/blob/master/doc/en/CAS/Optimising_Maxima.md) of Maxima.  If you do so get STACK working with the optimized version first.  Then complete the following:
  1. Get maximalocal.mac from STACK in Moodle, and copy/rename it, perhaps to server.mac (or to the version of the STACK code).
  2. Strip the final line `load("stackmaxima.mac")$` from copied version of `maximalocal.mac`.
  3. Modify the settings in `maximapool.conf` to load the saved image, and renamed `.mac` file.

## License

MaximaPool is Licensed under whatever license [moodle-qtype_stack](https://github.com/maths/moodle-qtype_stack/) is licensed. Currently, GNU General Public, License Version 3.

