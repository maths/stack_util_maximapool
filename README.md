MaximaPool
==========

These scrips were written as part of the STACK project. See https://github.com/maths/moodle-qtype_stack
A demonstration STACK server is currently hosted at https://stack.maths.ed.ac.uk/demo/

The MaximaPool creates a pool of maxima processes.  This has a number of advantages on 
large production sites, including the ability to put maxima processes on a separate 
server, or servers.  Also, pooling helps starting up maxima processes so that
STACK does not need to wait for them, this may save over 250ms each time you have to call maxima.

This configuration is intended for large implementations, and these documents assume familiarity
with installing STACK and using the ["optimised" mode](https://github.com/maths/moodle-qtype_stack/blob/master/doc/en/CAS/Optimising_Maxima.md). 
If you are new to STACK, or just evaluating STACK, the MaximaPool is not needed.

## Basic overview

Stack is a PHP-application and so it has no way of handling pooling of maxima processes (i.e. no threads). MaximaPool is a Java-servlet doing just that and only that.

MaximaPool does not check what it inputs to the processes it is running nor does it really care what it is running so you may also use it for other programs but most importantly you MUST MAKE SURE IT IS SECURED with no public access.

## How it works

Servlet keeps a set of processes running and uses them to evaluate commands it
receives as requests.  The servlet responds by returning the output of the process.
One may define a timeout for the evaluation of the commands and in that case the
servlet returns whatever the process has outputted up to that point (should the
timeout be reached the response code will be 416 otherwise the normal code 200
is used). The size of the pool kept may adapt to the frequency of the requests
and the frequency can be throttled.

Processes have a lifetime and once that is up they will be ended and new ones
started should the pool require new ones.

The main load caused by the servlet is due to the frequent updating of the
frequency estimate and simultaneous checking for old processes, this load may
be tuned by changing the frequency of these updates but that may cause problems
with demand spikes.

The servlet has a monitoring interface that shows some information about the
current state and allows direct test inputs. To access the monitoring interface
just make a GET-request for the servlet.


## Installation

### Requirements.

1. Get [tomcat8](http://tomcat.apache.org/) or some other servlet-container
supporting Java 1.6.

2. Get [maxima](http://maxima.sourceforge.net/) (and gnuplot).

3. Get [ant](http://ant.apache.org/) to build the servlet.

4. Get [jdk](http://openjdk.java.net/) to build the servlet.


Note: In debian based distributions you can get the requirements installed by:

`apt install tomcat8 ant openjdk-8-jdk git`

### Installation

1. Start by [installing STACK](https://github.com/maths/moodle-qtype_stack/blob/master/doc/en/Installation/index.md)
as normal and make sure that it works with the maxima you have installed.  We assume that (1) the root directory of the moodle site on the server is `$MOODLE`, (This should have the moodle `config.php` file in it.) and (2) the moodle data directory is `$MOODLEDATA` (this is `$CFG->dataroot` in Moodle's `config.php`).

2. Download or clone the MaximaPool files, for example to `$MAXIMAPOOL=/var/lib/maximapool`.

   `git clone https://github.com/maths/stack_util_maximapool.git $MAXIMAPOOL`

3. Copy `$MAXIMAPOOL/doc/servlet.example.conf` to `$MAXIMAPOOL/servlet.conf` and edit it.  There is not much to edit here.

4. Copy `$MAXIMAPOOL/doc/pool.example.conf` to `$MAXIMAPOOL/pool.conf` and edit it.  You are likely to keep the default settings for a demo install. 

5. Look at the end of `$MOODLE/question/type/stack/stack/maxima/stackmaxima.mac` to find the version number (`%%VERSION%%`).
   Create the directory  `$MAXIMAPOOL/%%VERSION%%`

6. Copy the library of maxima functions, the local maxima configuration files and maxima image distributed with STACK in `$MOODLE/question/type/stack/stack/maxima` and `$MOODLEDATA/stack/` to the pool folder.

   `cp -R $MOODLE/question/type/stack/stack/maxima/ $MAXIMAPOOL/%%VERSION%%/.`
   
   `cp -R $MOODLEDATA/stack/* $MAXIMAPOOL/%%VERSION%%/.`

7. Strip any final lines from `$MAXIMAPOOL/%%VERSION%%/maximalocal.mac` with load commands (such as `load("stackmaxima.mac")$` ).
   to make sure libraries are not loaded. (These lines should not be present if you are using the optimised version of maxima anyway.)
   
8. Please change in `$MAXIMAPOOL/%%VERSION%%/maximalocal.mac` the `file_search_maxima` and `file_search_lisp` paths where `$MOODLE/question/type/stack` is set, to `$MAXIMAPOOL/%%VERSION%%`.

9. Copy `$MAXIMAPOOL/doc/process.example.conf` to `$MAXIMAPOOL/%%VERSION%%/process.conf` and edit it.  There is more here to edit than the previous files. Note the location and the need to update `%%VERSION%%` and `%%MAXIMAPOOL%%` throughout. Furthermore please adapt the `command.line`.

10. Run ant to build the servlet.

11. Once the servlet has been built deploy the `MaximaPool.war` file to your servlet-container, with tomcat just copy it to the webapps-directory.
    Tomcat is likley to be in `/usr/share/tomcat8/` or `/var/lib/tomcat8/`
    
    `cp MaximaPool.war $TOMCAT/webapps/.`
    
12. Change file permissions to give ownership of `$MAXIMAPOOL` and all files to the tomcat user.  For example

    `chown -R tomcat8 $MAXIMAPOOL`

    `chgrp -R tomcat8 $MAXIMAPOOL`

13. Open `$URL=localhost:8080/MaximaPool/MaximaPool` (or whatever url you have configured).
    Once all the files are in place, you can go into the MaximaPool status page to start, test and stop pools.

14. SECURE THE POOL with http access controls to only accept connections from specific machines etc.!
    
Check that tomcat8 or (whatever user is running the servlet-container, and thus maxima) has the correct permissions to write to the working directories.
Otherwise plots cannot be generated. Or setup the servlet in remote operating mode so that it can transmit possible plots in the responses.

To use MaximaPool use the browser to navigate to the STACK question type configuration page.
Change STACK to use maxima Pool and set the maxima command (`qtype_stack | maximacommand`) in STACK to match the `$URL` where the servlet is running. 
Or use the Server mode with if you cannot setup the system so that it would write plots to the correct place.

Use the health check page to check this is working.  You probably need to clear the caches to check that the servlet actually works otherwise you may just get old values from cache instead of new ones from the servlet.

## Troubleshoot

If you open `localhost:8080/MaximaPool/MaximaPool` and you do not see text under the headings `Running versions` or `Non-running` then most probably your file layout was wrong. Have a look at `stack_util_maximapool/doc/server-setup.txt`. Also make sure that you correct `command.line` inside `process.conf` if you use clisp which compiles into byte code (see the comments in `process.conf` two lines above).

## License

MaximaPool is Licensed under whatever license
[moodle-qtype_stack](https://github.com/maths/moodle-qtype_stack/) is
licensed. Currently, GNU General Public, License Version 3.

