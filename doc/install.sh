#!/bin/bash
# Make sure only root can run our script
if [ "$(id -u)" != "0" ]; then
   echo "This script must be run as root" 1>&2
   exit 1
fi
DIRNAME="$(dirname "$(readlink -f "$0")")"

echo "WARNING: this script is written for debian based distributions."
echo "It will install tomcat8 and open-jdk8-jdk."
read -p "Do you want to resume (y/n)? " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]
then
    exit
fi

#change file changeline toline addafter
#changes file looks for changeline (regular expression) line by line
#then if line is found the line is replaced by toline
#if not found line is added after addafter
#if addafter is not supplied the
#then it is added after the last changeline
#if it is the first call to the function and addafter is not supplied
#then it is added after the last line
#todo if not found addafter add at end
globaddafter=$
function change {
	local file=$1
	local changeline=$2
	local toline=$3
	local addafter=${4-$globaddafter}
	globaddafter=$changeline
	

 	if grep -q "$changeline" $file;then
		sed -i "/$changeline/c$toline" $file;
	else
		if [ $addafter = "$" ]; then
			sed -i "$ a$toline" $file;
		else
			if grep -q "$addafter" $file;then
				sed -i "/$addafter/a$toline" $file;
			else
				sed -i "$ a$toline" $file;
			fi
		fi
	fi
}

apt install -y tomcat8 ant openjdk-8-jdk git
MOODLE=/var/www/html/moodle #this is where the config.php is
MOODLEDATA=/var/moodledata #see config.php
MAXIMAPOOL=/var/lib/maximapool

mkdir $MAXIMAPOOL
cd $MAXIMAPOOL
if [ ! -e $MAXIMAPOOL/.git ];then
       	git clone https://github.com/maths/stack_util_maximapool.git $MAXIMAPOOL
else
	echo "Git exist so not cloning but just updating"
	git pull
fi

if [ -e $DIRNAME/maximamoodle3credentials.gitig ];then
	source "$DIRNAME/maximamoodle3credentials.gitig"
else
	echo "Please enter admin password for maximapool:"
	read maximapoolpw
cat >"$DIRNAME/maximamoodle3credentials.gitig"<<EOF
maximapoolpw=$maximapoolpw
EOF
fi
cp -i $MAXIMAPOOL/doc/servlet.example.conf $MAXIMAPOOL/servlet.conf

change $MAXIMAPOOL/servlet.conf "directory.root" "directory.root = $MAXIMAPOOL/"
change $MAXIMAPOOL/servlet.conf "admin.password" "admin.password = $maximapoolpw"

cp -i $MAXIMAPOOL/doc/pool.example.conf $MAXIMAPOOL/pool.conf

#sed -n does not print exept when p command prints match
MAXIMAVERSION=$( sed -n 's/stackmaximaversion\:\([0-9]*\).*/\1/p' $MOODLE/question/type/stack/stack/maxima/stackmaxima.mac)

echo "Maxima version: $MAXIMAVERSION"
mkdir $MAXIMAPOOL/$MAXIMAVERSION
cp -R $MOODLE/question/type/stack/stack/maxima $MAXIMAPOOL/$MAXIMAVERSION
cp -R $MOODLEDATA/stack/* $MAXIMAPOOL/$MAXIMAVERSION
cp -i $MAXIMAPOOL/doc/process.example.conf $MAXIMAPOOL/$MAXIMAVERSION/process.conf

sed -i "s/%%VERSION%%/$MAXIMAVERSION/" $MAXIMAPOOL/$MAXIMAVERSION/process.conf

cd $MAXIMAPOOL
ant
cp MaximaPool.war /var/lib/tomcat8/webapps
chwon tomcat8:tomcat8 /var/lib/tomcat8/webapps/MaximaPool.war
chwon -R tomcat8:tomcat8 $MAXIMAPOOL

echo "open url: localhost:8080/MaximaPool/MaximaPool"
echo "Also go to :/Dashboard/Site administration/Plugins/Question types/STACK and"
echo "set platform to : Server and Maxima command to:localhost:8080/MaximaPool/MaximaPool"
